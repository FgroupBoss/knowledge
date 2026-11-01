package com.acme.kb.p0.document;

import com.acme.kb.p0.api.Dto;
import com.acme.kb.p0.audit.AuditService;
import com.acme.kb.p0.auth.Actor;
import com.acme.kb.p0.authz.AclDecision;
import com.acme.kb.p0.authz.AuthorizationService;
import com.acme.kb.p0.common.ApiException;
import com.acme.kb.p0.common.ErrorCode;
import com.acme.kb.p0.common.Hashing;
import com.acme.kb.p0.common.TraceIds;
import com.acme.kb.p0.repo.DocumentMapper;
import com.acme.kb.p0.repo.PipelineCommandMapper;
import com.acme.kb.p0.repo.TaskMapper;
import com.acme.kb.p0.storage.KeyRef;
import com.acme.kb.p0.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 文档生命周期编排：授权 → 校验 → MinIO 权威写入 → 同事务落 document/task/outbox。
 * 对象写成功但事务回滚时，对象成为孤儿，由对账脚本处置；DB 绝不留下指向未完成对象的记录。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final AuthorizationService authorization;
    private final UploadValidator validator;
    private final ObjectStorageService storage;
    private final DocumentWriteTx writeTx;
    private final DocumentMapper documents;
    private final TaskMapper tasks;
    private final PipelineCommandMapper pipeline;
    private final AuditService audit;

    public DocumentService(AuthorizationService authorization, UploadValidator validator,
                           ObjectStorageService storage, DocumentWriteTx writeTx, DocumentMapper documents,
                           TaskMapper tasks, PipelineCommandMapper pipeline, AuditService audit) {
        this.authorization = authorization;
        this.validator = validator;
        this.storage = storage;
        this.writeTx = writeTx;
        this.documents = documents;
        this.tasks = tasks;
        this.pipeline = pipeline;
        this.audit = audit;
    }

    public Dto.UploadAccepted upload(Actor actor, String workspaceId, String kbId, String idempotencyKey,
                                     MultipartFile file) {
        AclDecision decision = authorization.requireKbPermission(actor, kbId, "kb:write");
        if (!workspaceId.equals(decision.workspaceId())) {
            // 路径与授权归属不一致：按不存在处理，不泄露对方空间是否存在
            throw ApiException.notFound("知识库");
        }
        UploadValidator.requireIdempotencyKey(idempotencyKey);

        UploadValidator.Spool spool = validator.spool(file, idempotencyKey);
        try {
            String expectedDigest = Hashing.sha256Hex(DocumentWriteTx.uploadDigest(kbId, spool));
            String recordedDigest = pipeline.findIdempotentDigest(actor.tenantId(), actor.userId(),
                    DocumentWriteTx.SCOPE_UPLOAD, idempotencyKey);
            if (recordedDigest != null) {
                if (!recordedDigest.equals(expectedDigest)) {
                    throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "同一幂等键携带了不同内容");
                }
                String existingDocumentId = pipeline.findIdempotentResource(actor.tenantId(), actor.userId(),
                        DocumentWriteTx.SCOPE_UPLOAD, idempotencyKey);
                DocumentMapper.Row existing = documents.findById(existingDocumentId);
                if (existing == null) {
                    throw new ApiException(ErrorCode.INTERNAL_ERROR, "幂等记录指向的文档不存在");
                }
                TaskMapper.TaskRow task = tasks.latestForDocument(existing.id());
                audit.record(actor, "document.upload.replay", "document", existing.id(), "ALLOW", null,
                        Map.of("kbId", kbId, "reason", "idempotency-hit"));
                return new Dto.UploadAccepted(existing.id(), task == null ? null : task.id(),
                        task == null ? null : task.operationId(), existing.status(), true, existing.version());
            }

            DocumentMapper.Row latest = documents.findLatestByHash(kbId, spool.sha256());
            int version = latest == null ? 1 : latest.version() + 1;
            String documentId = UUID.randomUUID().toString();
            String objectKey = "originals/%s/%s/%s/v%d/%s".formatted(actor.tenantId(), kbId, documentId,
                    version, UUID.randomUUID());
            storage.putFile(objectKey, spool.file(), spool.size(), spool.mimeType());

            DocumentWriteTx.Created created;
            try {
                created = writeTx.createDocument(actor, documentId, workspaceId, kbId,
                        decision.classification(), spool, objectKey, idempotencyKey, version);
            } catch (DataIntegrityViolationException e) {
                log.warn("orphan_object_pending key={} reason=db_conflict", KeyRef.of(objectKey));
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "重复提交，请查询原文档状态");
            } catch (RuntimeException e) {
                log.warn("orphan_object_pending key={} reason=tx_failed", KeyRef.of(objectKey));
                throw e;
            }
            audit.record(actor, "document.upload", "document", created.documentId(), "ALLOW", null,
                    Map.of("kbId", kbId, "version", version, "bytes", spool.size(),
                            "traceId", TraceIds.current()));
            return new Dto.UploadAccepted(created.documentId(), created.taskId(), created.operationId(),
                    "UPLOADED", false, created.version());
        } finally {
            spool.discard();
        }
    }

    public Dto.DocumentView get(Actor actor, String documentId) {
        DocumentMapper.Row doc = authorization.requireDocumentRead(actor, documentId);
        return toView(doc, tasks.latestForDocument(doc.id()));
    }

    public Dto.TaskView task(Actor actor, String taskId) {
        TaskMapper.TaskRow task = tasks.findById(taskId);
        if (task == null) {
            throw ApiException.notFound("任务");
        }
        if (!task.tenantId().equals(actor.tenantId())) {
            audit.deny(actor, "task.read", "ingest_task", taskId, ErrorCode.CROSS_TENANT_ACCESS.name());
            throw ApiException.notFound("任务");
        }
        authorization.requireDocumentRead(actor, task.documentId());
        return toView(task);
    }

    public Dto.TaskView retry(Actor actor, String documentId, String idempotencyKey) {
        DocumentMapper.Row doc = authorization.requireDocumentWrite(actor, documentId);
        if (!"FAILED".equals(doc.status())) {
            throw new ApiException(ErrorCode.TASK_NOT_RETRYABLE, "仅 FAILED 状态文档可重试");
        }
        ErrorCode previous = ErrorCode.parse(doc.errorCode(), ErrorCode.INTERNAL_ERROR);
        if (previous.permanent()) {
            throw new ApiException(ErrorCode.TASK_NOT_RETRYABLE, "永久性错误需修复文件后重新上传");
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            UploadValidator.requireIdempotencyKey(idempotencyKey);
            String existing = pipeline.findIdempotentResource(actor.tenantId(), actor.userId(),
                    DocumentWriteTx.SCOPE_RETRY, idempotencyKey);
            if (existing != null) {
                TaskMapper.TaskRow replayed = tasks.findById(existing);
                if (replayed != null) {
                    return toView(replayed);
                }
            }
        }
        DocumentWriteTx.Created created = writeTx.enqueueRetry(actor, documentId, doc.version(),
                actor.tenantId(), idempotencyKey, "FAILED");
        audit.record(actor, "document.retry", "document", documentId, "ALLOW", null,
                Map.of("operationId", created.operationId(), "previousError", previous.name()));
        return toView(tasks.findById(created.taskId()));
    }

    /** 删除走 Saga：先置 DELETING（检索冻结由事件消费者在引擎侧执行），原件在确认不可检索后处理。 */
    public Dto.DocumentView delete(Actor actor, String documentId, boolean purge) {
        DocumentMapper.Row doc = authorization.requireDocumentDelete(actor, documentId);
        String status = doc.status();
        if ("DELETED".equals(status)) {
            return toView(doc, tasks.latestForDocument(documentId));
        }
        if (!"DELETING".equals(status)) {
            if (!"READY".equals(status) && !"FAILED".equals(status) && !"UPLOADED".equals(status)
                    && !"COMPENSATION_REQUIRED".equals(status)) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "入库进行中不允许删除，请等待收敛或先重试");
            }
            writeTx.enqueueDelete(actor, documentId, doc.version(), actor.tenantId(), status, purge,
                    "user-request");
            audit.record(actor, "document.delete.requested", "document", documentId, "ALLOW", null,
                    Map.of("purge", purge));
        }
        return toView(documents.findById(documentId), tasks.latestForDocument(documentId));
    }

    private Dto.DocumentView toView(DocumentMapper.Row doc, TaskMapper.TaskRow task) {
        return new Dto.DocumentView(doc.id(), doc.tenantId(), doc.workspaceId(), doc.kbId(),
                doc.displayName(), doc.version(), doc.status(), doc.sha256(), doc.sizeBytes(), doc.mimeType(),
                doc.classification(), doc.engineDocId(),
                task == null ? null : toView(task), doc.createdAt(), doc.updatedAt(), doc.errorCode(),
                doc.errorSummary());
    }

    private Dto.TaskView toView(TaskMapper.TaskRow t) {
        return new Dto.TaskView(t.id(), t.documentId(), t.operationId(), t.taskType(), t.status(),
                t.attempts(), t.maxAttempts(), t.nextRunAt(), t.leaseUntil(), t.errorCode(), t.errorSummary(),
                t.startedAt(), t.finishedAt());
    }
}
