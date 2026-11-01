package com.acme.kb.p1.api;

import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.auth.ActorHolder;
import com.acme.kb.p1.common.Hashing;
import com.acme.kb.p1.wiki.WikiService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Wiki 治理端点，与 `p1/contracts/openapi-1.2.yaml` 的 16 条 `wiki` 操作一一对应
 * （24 号脚本按"方法 + 路径"双向比对，多一条少一条都是 ERROR）。
 * Controller 只做参数装配与 ETag，授权、状态机与可见性判定全部在 `WikiService`/`WikiWriteTx`。
 */
@ConditionalOnProperty(prefix = "kb.wiki", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/api/v1")
public class WikiController {

    private final WikiService wiki;

    public WikiController(WikiService wiki) {
        this.wiki = wiki;
    }

    @GetMapping("/knowledge-bases/{kbId}/wiki-pages")
    public Dto.WikiPageList list(@PathVariable String kbId,
                                 @RequestParam(name = "status", required = false) String status,
                                 @RequestParam(name = "freshness", required = false) String freshness,
                                 @RequestParam(name = "owner", required = false) String owner,
                                 @RequestParam(name = "cursor", required = false) String cursor,
                                 @RequestParam(name = "limit", required = false) Integer limit) {
        return wiki.list(ActorHolder.require(), kbId, status, freshness, owner, cursor, limit);
    }

    @PostMapping("/knowledge-bases/{kbId}/wiki-pages")
    public ResponseEntity<Dto.WikiPageView> create(@PathVariable String kbId,
                                                  @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                  @RequestBody Dto.WikiPageCreateRequest request) {
        Actor actor = ActorHolder.require();
        Dto.WikiPageView view = wiki.create(actor, kbId, idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.CREATED).eTag(etag(actor, view)).body(view);
    }

    @GetMapping("/wiki-pages/{pageId}")
    public ResponseEntity<Dto.WikiPageView> get(@PathVariable String pageId) {
        Actor actor = ActorHolder.require();
        Dto.WikiPageView view = wiki.detail(actor, pageId);
        return ResponseEntity.ok().eTag(etag(actor, view)).body(view);
    }

    @PutMapping("/wiki-pages/{pageId}/draft")
    public ResponseEntity<Dto.WikiPageView> saveDraft(@PathVariable String pageId,
                                                     @RequestHeader("If-Match") String ifMatch,
                                                     @RequestBody Dto.WikiDraftRequest request) {
        Actor actor = ActorHolder.require();
        Dto.WikiPageView view = wiki.saveDraft(actor, pageId, ifMatch, request);
        return ResponseEntity.ok().eTag(etag(actor, view)).body(view);
    }

    @PostMapping("/wiki-pages/{pageId}/sources")
    public ResponseEntity<Dto.WikiSourceView> addSource(@PathVariable String pageId,
                                                        @RequestBody Dto.WikiSourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(wiki.addSource(ActorHolder.require(), pageId, request));
    }

    @DeleteMapping("/wiki-pages/{pageId}/sources/{sourceId}")
    public ResponseEntity<Void> removeSource(@PathVariable String pageId, @PathVariable String sourceId) {
        wiki.removeSource(ActorHolder.require(), pageId, sourceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/wiki-pages/{pageId}/submit")
    public Dto.WikiPageView submit(@PathVariable String pageId, @RequestHeader("If-Match") String ifMatch,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return wiki.submit(ActorHolder.require(), pageId, ifMatch, idempotencyKey);
    }

    @GetMapping("/workspaces/{workspaceId}/wiki-review-queue")
    public Dto.WikiReviewQueue reviewQueue(@PathVariable String workspaceId,
                                          @RequestParam(name = "cursor", required = false) String cursor,
                                          @RequestParam(name = "limit", required = false) Integer limit) {
        return wiki.reviewQueue(ActorHolder.require(), workspaceId, cursor, limit);
    }

    @PostMapping("/wiki-revisions/{revisionId}/approve")
    public Dto.WikiPageView approve(@PathVariable String revisionId,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return wiki.approve(ActorHolder.require(), revisionId, idempotencyKey);
    }

    @PostMapping("/wiki-revisions/{revisionId}/reject")
    public Dto.WikiPageView reject(@PathVariable String revisionId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                   @RequestBody Dto.WikiDecisionRequest request) {
        return wiki.reject(ActorHolder.require(), revisionId, idempotencyKey,
                request == null ? null : request.decisionNote());
    }

    @PostMapping("/wiki-pages/{pageId}/retract")
    public Dto.WikiPageView retract(@PathVariable String pageId,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return wiki.retract(ActorHolder.require(), pageId, idempotencyKey);
    }

    @PostMapping("/wiki-pages/{pageId}/archive")
    public Dto.WikiPageView archive(@PathVariable String pageId,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return wiki.archive(ActorHolder.require(), pageId, idempotencyKey);
    }

    @PatchMapping("/wiki-pages/{pageId}/governance")
    public Dto.WikiPageView governance(@PathVariable String pageId,
                                       @RequestHeader("If-Match") String ifMatch,
                                       @RequestBody Map<String, Object> body) {
        return wiki.governance(ActorHolder.require(), pageId, ifMatch, body);
    }

    @GetMapping("/wiki-pages/{pageId}/revisions")
    public Dto.WikiRevisionList revisions(@PathVariable String pageId,
                                          @RequestParam(name = "cursor", required = false) String cursor,
                                          @RequestParam(name = "limit", required = false) Integer limit) {
        return wiki.revisions(ActorHolder.require(), pageId, cursor, limit);
    }

    @GetMapping("/wiki-pages/{pageId}/revisions/{revisionNo}")
    public Dto.WikiRevisionView revision(@PathVariable String pageId, @PathVariable int revisionNo) {
        return wiki.revision(ActorHolder.require(), pageId, revisionNo);
    }

    @GetMapping("/wiki-pages/{pageId}/revisions/{fromRevisionNo}/diff/{toRevisionNo}")
    public Dto.WikiDiffView diff(@PathVariable String pageId, @PathVariable int fromRevisionNo,
                                 @PathVariable int toRevisionNo) {
        return wiki.diff(ActorHolder.require(), pageId, fromRevisionNo, toRevisionNo);
    }

    /**
     * ETag 形如 `"v{治理位}-{actorHash}-{修订号}"`：治理位供 PATCH 的 If-Match 使用，
     * 修订号供草稿保存的 If-Match 使用，带 actorHash 是因为草稿正文的可见性因人而异，
     * 不能把不同主体的响应放进同一个缓存键。
     */
    private static String etag(Actor actor, Dto.WikiPageView view) {
        String actorHash = Hashing.sha256Hex(actor.key()).substring(0, 8);
        return "\"v%d-%s-%d\"".formatted(view.aclVersionGuard(), actorHash, view.currentRevisionNo());
    }
}
