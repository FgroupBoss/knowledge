package com.acme.kb.p0.api;

import com.acme.kb.p0.auth.Actor;
import com.acme.kb.p0.auth.ActorHolder;
import com.acme.kb.p0.document.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 文档与任务状态端点；权限判定全部下沉到 DocumentService/AuthorizationService。 */
@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping("/workspaces/{workspaceId}/knowledge-bases/{kbId}/documents")
    public ResponseEntity<Dto.UploadAccepted> upload(@PathVariable String workspaceId, @PathVariable String kbId,
                                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                     @RequestPart("file") MultipartFile file) {
        Actor actor = ActorHolder.require();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(documents.upload(actor, workspaceId, kbId, idempotencyKey, file));
    }

    @GetMapping("/documents/{documentId}")
    public ResponseEntity<Dto.DocumentView> get(@PathVariable String documentId,
                                               @RequestHeader(name = "If-None-Match", required = false)
                                               String ifNoneMatch) {
        Actor actor = ActorHolder.require();
        Dto.DocumentView view = documents.get(actor, documentId);
        String etag = etagOf(view);
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).body(view);
    }

    @DeleteMapping("/documents/{documentId}")
    public ResponseEntity<Dto.DocumentView> delete(@PathVariable String documentId,
                                                  @RequestParam(name = "purge", defaultValue = "false")
                                                  boolean purge) {
        Actor actor = ActorHolder.require();
        return ResponseEntity.accepted().body(documents.delete(actor, documentId, purge));
    }

    @PostMapping("/documents/{documentId}/retry")
    public ResponseEntity<Dto.TaskView> retry(@PathVariable String documentId,
                                             @RequestHeader(name = "Idempotency-Key", required = false)
                                             String idempotencyKey) {
        Actor actor = ActorHolder.require();
        return ResponseEntity.accepted().body(documents.retry(actor, documentId, idempotencyKey));
    }

    @GetMapping("/tasks/{taskId}")
    public Dto.TaskView task(@PathVariable String taskId) {
        return documents.task(ActorHolder.require(), taskId);
    }

    /** ETag 由版本+状态+更新时间导出，客户端可安全轮询入库进度。 */
    private static String etagOf(Dto.DocumentView view) {
        long updated = view.updatedAt() == null ? 0 : view.updatedAt().toEpochMilli();
        return "\"v%d-%s-%d\"".formatted(view.version(), view.status(), updated);
    }
}
