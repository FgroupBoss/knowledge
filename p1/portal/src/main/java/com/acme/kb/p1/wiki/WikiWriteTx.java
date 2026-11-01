package com.acme.kb.p1.wiki;

import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.common.Hashing;
import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.repo.DocumentMapper;
import com.acme.kb.p1.repo.PipelineCommandMapper;
import com.acme.kb.p1.repo.WikiCommandMapper;
import com.acme.kb.p1.repo.WikiQueryMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wiki 治理的事务边界（与 `DocumentWriteTx` 同构）：页面/修订/来源/审核/同步任务与审计在同一 MySQL
 * 事务内落定，因此不存在"状态已改而审计缺失"的中间态（AGENTS.md §8、契约 §1）。
 *
 * 两条硬约束：
 * 1. 所有写都是**条件更新**（`WHERE status = 前置态` / `WHERE current_revision_no = :ifMatch`
 *    / `WHERE acl_version = :ifMatch`），影响 0 行即视为并发失败，绝不静默覆盖；
 * 2. DENY 审计走独立事务（`denyStrict`），保证回滚后拒绝记录仍在——负向用例 1/2 断言的就是这一点。
 */
@Service
public class WikiWriteTx {

    public static final String SCOPE_CREATE = "wiki-page-create";
    public static final String SCOPE_SUBMIT = "wiki-page-submit";
    public static final String SCOPE_APPROVE = "wiki-revision-approve";
    public static final String SCOPE_REJECT = "wiki-revision-reject";
    public static final String SCOPE_RETRACT = "wiki-page-retract";
    public static final String SCOPE_ARCHIVE = "wiki-page-archive";

    public static final String OP_PUBLISH = "PUBLISH";
    public static final String OP_RETRACT = "RETRACT";

    /** 写路径的统一返回：三轴状态变化后由服务层重新读取视图。 */
    public record Written(String pageId, String revisionId, int revisionNo) {
    }

    private final WikiCommandMapper commands;
    private final WikiQueryMapper queries;
    private final WikiSourceGuard sources;
    private final AuditService audit;
    private final PipelineCommandMapper pipeline;
    private final KbProperties props;

    public WikiWriteTx(WikiCommandMapper commands, WikiQueryMapper queries, WikiSourceGuard sources,
                       AuditService audit, PipelineCommandMapper pipeline, KbProperties props) {
        this.commands = commands;
        this.queries = queries;
        this.sources = sources;
        this.audit = audit;
        this.pipeline = pipeline;
        this.props = props;
    }

    /** 建页即建首个 DRAFT 修订；slug 在同库内唯一，冲突走 400（V17 唯一键的友好包装）。 */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Written createPage(Actor actor, String workspaceId, String kbId, String slug, String title,
                              String body, String summary, String ownerUserId, Instant validUntil,
                              Instant reviewAt, String idempotencyKey) {
        Instant now = utcNow();
        String pageId = UUID.randomUUID().toString();
        String revisionId = UUID.randomUUID().toString();
        String author = ownerUserId == null || ownerUserId.isBlank() ? actor.userId() : ownerUserId;
        String text = body == null ? "" : body;
        String digest = Hashing.sha256Hex(text);

        Map<String, Object> page = new HashMap<>();
        page.put("id", pageId);
        page.put("tenantId", actor.tenantId());
        page.put("workspaceId", workspaceId);
        page.put("kbId", kbId);
        page.put("slug", slug);
        page.put("title", title);
        page.put("status", WikiPageStatus.DRAFT.name());
        page.put("freshness", WikiFreshness.FRESH.name());
        page.put("ownerUserId", author);
        page.put("currentRevisionNo", 1);
        page.put("validUntil", validUntil);
        page.put("reviewAt", reviewAt);
        page.put("now", now);
        try {
            commands.insertPage(page);
        } catch (DuplicateKeyException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "同一知识库内该 slug 已存在");
        }

        Map<String, Object> revision = new HashMap<>();
        revision.put("id", revisionId);
        revision.put("pageId", pageId);
        revision.put("revisionNo", 1);
        revision.put("authorUserId", actor.userId());
        revision.put("status", WikiRevisionStatus.DRAFT.name());
        revision.put("body", text);
        revision.put("bodyDigest", digest);
        revision.put("summary", summary);
        revision.put("parentRevisionId", null);
        revision.put("now", now);
        commands.insertRevision(revision);

        recordIdempotency(actor, SCOPE_CREATE, idempotencyKey, pageId, createDigest(kbId, slug, digest));
        audit.recordInTx(actor, "wiki.page.create", "wiki_page", pageId, "ALLOW", null,
                Map.of("kbId", kbId, "revisionNo", 1, "bodyDigest12", prefix12(digest),
                        "bytes", text.getBytes(StandardCharsets.UTF_8).length));
        return new Written(pageId, revisionId, 1);
    }

    /**
     * 保存草稿（D-07）：`DRAFT` 原地更新，`APPROVED/SUPERSEDED/REJECTED` 开新修订，
     * `PENDING`（审核中）拒绝——退回后才能改。乐观锁条件不满足即 409，响应只给计数与摘要前 12 位。
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Written saveDraft(Actor actor, WikiQueryMapper.PageRow page, int ifMatch, String title,
                             String body, String summary) {
        WikiQueryMapper.RevisionRow current = queries.findRevisionByNo(page.id(), ifMatch);
        if (page.currentRevisionNo() != ifMatch || current == null) {
            throw revisionLost(page, queries.findLatestRevision(page.id()));
        }
        Instant now = utcNow();
        String text = body == null ? "" : body;
        String digest = Hashing.sha256Hex(text);
        WikiRevisionStatus status = WikiRevisionStatus.parse(current.status());

        if (status.editable()) {
            if (commands.updateRevisionBody(current.id(), text, digest, summary) == 0
                    || commands.updatePageTitleInPlace(page.id(), title, ifMatch, now) == 0) {
                throw revisionLost(page, queries.findLatestRevision(page.id()));
            }
            saved(actor, current, page, ifMatch, digest, text, "in-place");
            return new Written(page.id(), current.id(), ifMatch);
        }
        if (!status.newDraftAllowed()) {
            audit.denyStrict(actor, "wiki.revision.save", "wiki_revision", current.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "审核中的修订需先退回草稿才能修改");
        }
        int next = ifMatch + 1;
        if (commands.appendRevision(page.id(), title, ifMatch, now) == 0) {
            throw revisionLost(page, queries.findLatestRevision(page.id()));
        }
        String revisionId = UUID.randomUUID().toString();
        Map<String, Object> revision = new HashMap<>();
        revision.put("id", revisionId);
        revision.put("pageId", page.id());
        revision.put("revisionNo", next);
        revision.put("authorUserId", actor.userId());
        revision.put("status", WikiRevisionStatus.DRAFT.name());
        revision.put("body", text);
        revision.put("bodyDigest", digest);
        revision.put("summary", summary);
        revision.put("parentRevisionId", current.id());
        revision.put("now", now);
        commands.insertRevision(revision);
        saved(actor, current, page, next, digest, text, "new-revision");
        return new Written(page.id(), revisionId, next);
    }

    /** 挂接来源：actor 必须能读到该文档（否则可拿他人文档当"证据"骗过审核），且只作用于 DRAFT 修订。 */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public WikiQueryMapper.SourceRow attachSource(Actor actor, WikiQueryMapper.RevisionRow revision,
                                                  String sourceType, String sourceId, Integer sourceVersion,
                                                  Integer chunkIndex, String locator) {
        requireEditableRevision(actor, revision, "wiki.source.attach");
        DocumentMapper.Row doc = sources.requireAttachable(actor, sourceType, sourceId, sourceVersion);
        Map<String, Object> params = new HashMap<>();
        params.put("id", UUID.randomUUID().toString());
        params.put("tenantId", actor.tenantId());
        params.put("revisionId", revision.id());
        params.put("sourceType", sourceType);
        params.put("sourceId", sourceId);
        params.put("sourceVersion", doc.version());
        params.put("chunkIndex", chunkIndex);
        params.put("locator", locator);
        params.put("contentDigest", doc.sha256());
        params.put("aclVersion", sources.aclVersionAt(actor, doc.kbId()));
        params.put("now", utcNow());
        commands.insertSource(params);
        // ON DUPLICATE KEY UPDATE 命中时不返回新 id，回查同键行保证响应稳定幂等
        WikiQueryMapper.SourceRow row = findSourceRow(revision.id(), sourceType, sourceId, doc.version());
        audit.recordInTx(actor, "wiki.source.attach", "wiki_source", row.id(), "ALLOW", null,
                Map.of("revisionId", revision.id(), "sourceType", sourceType, "sourceId", sourceId,
                        "sourceVersion", doc.version()));
        return row;
    }

    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void detachSource(Actor actor, WikiQueryMapper.RevisionRow revision, String sourceId) {
        requireEditableRevision(actor, revision, "wiki.source.detach");
        WikiQueryMapper.SourceRow row = queries.findSource(sourceId);
        if (row == null || !row.revisionId().equals(revision.id())
                || !row.tenantId().equals(actor.tenantId())) {
            throw ApiException.notFound("来源");
        }
        if (commands.deleteSource(sourceId, revision.id()) == 0) {
            throw ApiException.notFound("来源");
        }
        audit.recordInTx(actor, "wiki.source.detach", "wiki_source", sourceId, "ALLOW", null,
                Map.of("revisionId", revision.id(), "sourceType", row.sourceType(), "sourceId",
                        row.sourceId()));
    }

    /** E1 + R1 同事务：页面进 `IN_REVIEW`、修订进 `PENDING`，来源可得性是硬门槛。 */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Written submit(Actor actor, WikiQueryMapper.PageRow page, int ifMatch, String idempotencyKey) {
        WikiQueryMapper.RevisionRow revision = queries.findRevisionByNo(page.id(), ifMatch);
        if (page.currentRevisionNo() != ifMatch || revision == null) {
            throw revisionLost(page, queries.findLatestRevision(page.id()));
        }
        String pageStatus = page.status();
        boolean entersReview = WikiStateMachine.canTransition(WikiStateMachine.PAGE, pageStatus,
                WikiPageStatus.IN_REVIEW.name());
        // E4 的前置态是「页面已发布 + 新修订待审」：PUBLISHED 页面提修订时页面态不动，只走 R1。
        // 若这里也强制要求 PAGE 有入边，E4/R4 两条边就永远不可能产生（§2 的边必须可产生）。
        if (!entersReview && !WikiPageStatus.PUBLISHED.name().equals(pageStatus)) {
            audit.denyStrict(actor, "wiki.submit", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "当前状态不允许提交审核");
        }
        if (!WikiRevisionStatus.parse(revision.status()).editable()) {
            audit.denyStrict(actor, "wiki.submit", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "只有草稿修订可以提交审核");
        }
        if (page.validUntil() != null && page.validUntil().isBefore(utcNow())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "页面有效期已过，请先更新治理位");
        }
        sources.requireSubmittable(actor, revision.id());

        Instant now = utcNow();
        if (entersReview && commands.transitionPage(page.id(), WikiPageStatus.DRAFT.name(),
                WikiPageStatus.IN_REVIEW.name(), now) == 0) {
            audit.denyStrict(actor, "wiki.submit", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "页面状态已变化，请重新查询");
        }
        if (commands.transitionRevision(revision.id(), WikiRevisionStatus.DRAFT.name(),
                WikiRevisionStatus.PENDING.name()) == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "修订状态已变化，请重新查询");
        }
        recordIdempotency(actor, SCOPE_SUBMIT, idempotencyKey, page.id(), submitDigest(page.id(), ifMatch));
        audit.recordInTx(actor, "wiki.submit", "wiki_page", page.id(), "ALLOW", null,
                Map.of("revisionNo", ifMatch, "revisionId", revision.id(),
                        "pageEdge", entersReview ? "E1" : "NONE",
                        "sources", queries.countSources(revision.id())));
        return new Written(page.id(), revision.id(), ifMatch);
    }

    /**
     * E3/E4 + R2/R4 同事务：通过即移动可见性指针并写 `PUBLISH` 同步任务。
     * 重复 approve 的幂等在服务层短路（见 `WikiService.approve`），因此这里只处理首次通过。
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Written approve(Actor actor, WikiQueryMapper.PageRow page, WikiQueryMapper.RevisionRow revision,
                           WikiCapabilities.Grants grants, String idempotencyKey) {
        if (!grants.review()) {
            audit.denyStrict(actor, "wiki.approve", "wiki_page", page.id(),
                    ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED.name());
            throw new ApiException(ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED,
                    "当前身份未被指派为审核人，不能审核该修订");
        }
        if (actor.userId().equals(revision.authorUserId())) {
            audit.denyStrict(actor, "wiki.approve", "wiki_page", page.id(),
                    ErrorCode.WIKI_REVIEW_SELF_APPROVAL.name());
            throw new ApiException(ErrorCode.WIKI_REVIEW_SELF_APPROVAL, "作者不能审核自己的修订");
        }
        String from = page.status();
        if (!WikiStateMachine.canTransition(WikiStateMachine.PAGE, from, WikiPageStatus.PUBLISHED.name())) {
            audit.denyStrict(actor, "wiki.approve", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "该页面当前不在待审状态");
        }
        if (!WikiRevisionStatus.parse(revision.status()).reviewable()) {
            audit.denyStrict(actor, "wiki.approve", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "审核只作用于待审修订");
        }
        sources.requireSubmittable(actor, revision.id());

        Instant now = utcNow();
        // 先落审核人再改状态：markReviewed 的 WHERE 带 status='PENDING'，反过来写会让审核人永远落不进库
        if (commands.markReviewed(revision.id(), actor.userId(), now, null) == 0
                || commands.transitionRevision(revision.id(), WikiRevisionStatus.PENDING.name(),
                WikiRevisionStatus.APPROVED.name()) == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "修订状态已变化，请重新查询");
        }
        supersedePrevious(actor, page, revision);
        if (commands.publishPage(page.id(), from, revision.id(), revision.revisionNo(), now) == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "页面状态已变化，请重新查询");
        }
        enqueueTask(actor.tenantId(), page.id(), revision.revisionNo(), OP_PUBLISH, now);
        recordIdempotency(actor, SCOPE_APPROVE, idempotencyKey, revision.id(), approveDigest(revision.id()));
        audit.recordInTx(actor, "wiki.approve", "wiki_page", page.id(), "ALLOW", null,
                Map.of("revisionNo", revision.revisionNo(), "revisionId", revision.id(),
                        "edge", WikiPageStatus.IN_REVIEW.name().equals(from) ? "E3" : "E4",
                        "syncTask", OP_PUBLISH));
        return new Written(page.id(), revision.id(), revision.revisionNo());
    }

    /** E2 + R3：退回草稿，理由原文不落库，只落摘要哈希（红线 1）。 */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Written reject(Actor actor, WikiQueryMapper.PageRow page, WikiQueryMapper.RevisionRow revision,
                          WikiCapabilities.Grants grants, String decisionNote, String idempotencyKey) {
        if (!grants.review()) {
            audit.denyStrict(actor, "wiki.reject", "wiki_page", page.id(),
                    ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED.name());
            throw new ApiException(ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED,
                    "当前身份未被指派为审核人，不能审核该修订");
        }
        if (!WikiStateMachine.canTransition(WikiStateMachine.PAGE, page.status(),
                WikiPageStatus.DRAFT.name())
                || !WikiRevisionStatus.parse(revision.status()).reviewable()) {
            audit.denyStrict(actor, "wiki.reject", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "该页面或修订当前不可退回");
        }
        Instant now = utcNow();
        String noteDigest = Hashing.sha256Hex(decisionNote);
        if (commands.markReviewed(revision.id(), actor.userId(), now, noteDigest) == 0
                || commands.transitionRevision(revision.id(), WikiRevisionStatus.PENDING.name(),
                WikiRevisionStatus.REJECTED.name()) == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "修订状态已变化，请重新查询");
        }
        if (commands.transitionPage(page.id(), WikiPageStatus.IN_REVIEW.name(), WikiPageStatus.DRAFT.name(),
                now) == 0) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "页面状态已变化，请重新查询");
        }
        recordIdempotency(actor, SCOPE_REJECT, idempotencyKey, revision.id(), rejectDigest(revision.id()));
        audit.recordInTx(actor, "wiki.reject", "wiki_page", page.id(), "ALLOW", null,
                Map.of("revisionNo", revision.revisionNo(), "revisionId", revision.id(),
                        "noteDigest12", prefix12(noteDigest)));
        return new Written(page.id(), revision.id(), revision.revisionNo());
    }

    /**
     * E5：进 `RETRACTING` 并同事务写 `RETRACT` 同步任务。检索层从 `RETRACTING` 起一律拒绝，
     * 不依赖引擎是否已删干净；`E6` 由 W2 的消费者在真引擎上确认，本轮任务保持 `PENDING`。
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Written retract(Actor actor, WikiQueryMapper.PageRow page, WikiCapabilities.Grants grants,
                           String idempotencyKey) {
        if (!grants.retract()) {
            audit.denyStrict(actor, "wiki.retract", "wiki_page", page.id(),
                    ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前身份不允许撤稿");
        }
        if (!WikiStateMachine.canTransition(WikiStateMachine.PAGE, page.status(),
                WikiPageStatus.RETRACTING.name())) {
            audit.denyStrict(actor, "wiki.retract", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "只有已发布页面可以撤稿");
        }
        Instant now = utcNow();
        if (commands.transitionPage(page.id(), WikiPageStatus.PUBLISHED.name(),
                WikiPageStatus.RETRACTING.name(), now) == 0) {
            audit.denyStrict(actor, "wiki.retract", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "页面状态已变化，请重新查询");
        }
        int target = page.publishedRevisionNo() == null ? page.currentRevisionNo()
                : page.publishedRevisionNo();
        enqueueTask(actor.tenantId(), page.id(), target, OP_RETRACT, now);
        recordIdempotency(actor, SCOPE_RETRACT, idempotencyKey, page.id(), retractDigest(page.id(), target));
        audit.recordInTx(actor, "wiki.retract", "wiki_page", page.id(), "ALLOW", null,
                Map.of("revisionNo", target, "syncTask", OP_RETRACT));
        return new Written(page.id(), page.publishedRevisionId(), target);
    }

    /** E7/E8：只有 `DRAFT` 与 `RETRACTED` 可归档（§3 禁止 PUBLISHED/RETRACTING 直接归档）。 */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public Written archive(Actor actor, WikiQueryMapper.PageRow page, WikiCapabilities.Grants grants,
                           String idempotencyKey) {
        if (!grants.retract()) {
            audit.denyStrict(actor, "wiki.archive", "wiki_page", page.id(),
                    ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "当前身份不允许归档");
        }
        if (!WikiStateMachine.canTransition(WikiStateMachine.PAGE, page.status(),
                WikiPageStatus.ARCHIVED.name())) {
            audit.denyStrict(actor, "wiki.archive", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "该状态不可直接归档：已发布页面须先撤稿并确认索引消失");
        }
        Instant now = utcNow();
        if (commands.transitionPage(page.id(), page.status(), WikiPageStatus.ARCHIVED.name(), now) == 0) {
            audit.denyStrict(actor, "wiki.archive", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "页面状态已变化，请重新查询");
        }
        recordIdempotency(actor, SCOPE_ARCHIVE, idempotencyKey, page.id(), archiveDigest(page.id()));
        audit.recordInTx(actor, "wiki.archive", "wiki_page", page.id(), "ALLOW", null,
                Map.of("fromStatus", page.status()));
        return new Written(page.id(), page.publishedRevisionId(), page.currentRevisionNo());
    }

    /**
     * 治理位（负责人 / 有效期 / 复查日）：If-Match 打在 `wiki_page.acl_version` 上，
     * 0 行即 VERSION_CONFLICT。审计按实际改动的位拆成两个动作名。
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public void governance(Actor actor, WikiQueryMapper.PageRow page, long expectedAclVersion,
                           String ownerUserId, Instant validUntil, boolean clearValidUntil,
                           Instant reviewAt, boolean clearReviewAt) {
        Instant now = utcNow();
        if (commands.patchGovernance(page.id(), expectedAclVersion, ownerUserId, validUntil,
                clearValidUntil, reviewAt, clearReviewAt, now) == 0) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT, "页面治理位已被他人修改，请重新加载");
        }
        Map<String, Object> metadata = Map.of("aclVersion", expectedAclVersion + 1);
        if (ownerUserId != null && !ownerUserId.isBlank()) {
            audit.recordInTx(actor, "wiki.owner.change", "wiki_page", page.id(), "ALLOW", null, metadata);
        }
        if (validUntil != null || clearValidUntil || reviewAt != null || clearReviewAt) {
            audit.recordInTx(actor, "wiki.validity.update", "wiki_page", page.id(), "ALLOW", null, metadata);
        }
    }

    /** R4：新修订生效时旧 APPROVED 修订置 SUPERSEDED，不删行（历史与差异计算依赖它）。 */
    private void supersedePrevious(Actor actor, WikiQueryMapper.PageRow page,
                                   WikiQueryMapper.RevisionRow revision) {
        String previousId = page.publishedRevisionId();
        if (previousId == null || previousId.equals(revision.id())) {
            return;
        }
        if (commands.transitionRevision(previousId, WikiRevisionStatus.APPROVED.name(),
                WikiRevisionStatus.SUPERSEDED.name()) == 0) {
            // 已生效修订不在 APPROVED：数据不一致，宁可回滚整笔审核也不留下两个 APPROVED 指针
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "上一个已生效修订状态异常，审核已取消");
        }
        audit.recordInTx(actor, "wiki.approve", "wiki_revision", previousId, "ALLOW", null,
                Map.of("edge", "R4", "pageId", page.id()));
    }

    private void enqueueTask(String tenantId, String pageId, int revisionNo, String operation,
                             Instant now) {
        Map<String, Object> task = new HashMap<>();
        task.put("id", UUID.randomUUID().toString());
        task.put("tenantId", tenantId);
        task.put("pageId", pageId);
        task.put("revisionNo", revisionNo);
        task.put("operation", operation);
        task.put("maxAttempts", props.wiki().maxAttempts());
        task.put("nextRunAt", now);
        task.put("correlationId", UUID.randomUUID().toString());
        task.put("now", now);
        commands.insertSyncTask(task);
    }

    private void requireEditableRevision(Actor actor, WikiQueryMapper.RevisionRow revision, String action) {
        if (!WikiRevisionStatus.parse(revision.status()).editable()) {
            audit.denyStrict(actor, action, "wiki_revision", revision.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "只有草稿修订可以调整来源");
        }
    }

    private WikiQueryMapper.SourceRow findSourceRow(String revisionId, String sourceType, String sourceId,
                                                    int sourceVersion) {
        List<WikiQueryMapper.SourceRow> rows = queries.listSources(revisionId);
        for (WikiQueryMapper.SourceRow row : rows) {
            if (row.sourceType().equals(sourceType) && row.sourceId().equals(sourceId)
                    && sourceVersion == row.sourceVersion()) {
                return row;
            }
        }
        throw new ApiException(ErrorCode.INTERNAL_ERROR, "来源挂接结果不可见");
    }

    private void saved(Actor actor, WikiQueryMapper.RevisionRow before, WikiQueryMapper.PageRow page,
                       int revisionNo, String digest, String text, String mode) {
        audit.recordInTx(actor, "wiki.revision.save", "wiki_revision", before.id(), "ALLOW", null,
                Map.of("pageId", page.id(), "revisionNo", revisionNo, "mode", mode,
                        "bodyDigest12", prefix12(digest),
                        "bytes", text.getBytes(StandardCharsets.UTF_8).length));
    }

    // 幂等请求指纹：服务层预检与事务内落库共用同一算法，两侧算出的摘要必须逐字节一致。
    public static String createDigest(String kbId, String slug, String bodyDigest) {
        return "kb=" + kbId + "|slug=" + slug + "|sha=" + bodyDigest;
    }

    public static String submitDigest(String pageId, int revisionNo) {
        return "submit:" + pageId + ":" + revisionNo;
    }

    public static String approveDigest(String revisionId) {
        return "approve:" + revisionId;
    }

    public static String rejectDigest(String revisionId) {
        return "reject:" + revisionId;
    }

    public static String retractDigest(String pageId, int revisionNo) {
        return "retract:" + pageId + ":" + revisionNo;
    }

    public static String archiveDigest(String pageId) {
        return "archive:" + pageId;
    }

    private void recordIdempotency(Actor actor, String scope, String key, String resourceId,
                                   String digest) {
        if (key == null || key.isBlank()) {
            return;
        }
        pipeline.insertIdempotency(UUID.randomUUID().toString(), actor.tenantId(), actor.userId(), scope,
                key, resourceId, Hashing.sha256Hex(digest), utcNow());
    }

    /** 响应只给当前修订号与最新正文摘要前 12 位，绝不回传他人正文（D-07、用例 3）。 */
    private static ApiException revisionLost(WikiQueryMapper.PageRow page,
                                             WikiQueryMapper.RevisionRow latest) {
        WikiQueryMapper.RevisionRow row = latest;
        String message = "草稿已被更新，请比较差异后重试";
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("currentRevisionNo", String.valueOf(page.currentRevisionNo()));
        if (row == null) {
            return new ApiException(ErrorCode.WIKI_REVISION_LOST, message, detail);
        }
        detail.put("latestRevisionNo", String.valueOf(row.revisionNo()));
        detail.put("latestBodyDigest12", prefix12(row.bodyDigest()));
        return new ApiException(ErrorCode.WIKI_REVISION_LOST, message, detail);
    }

    private static String prefix12(String digest) {
        return digest == null ? "" : digest.substring(0, Math.min(12, digest.length()));
    }

    /** DATETIME(3) 会对第 4 位小数四舍五入，参与审计哈希链的时间必须先截断（AGENTS.md §7）。 */
    private static Instant utcNow() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }
}
