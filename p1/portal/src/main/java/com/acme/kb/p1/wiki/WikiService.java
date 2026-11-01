package com.acme.kb.p1.wiki;

import com.acme.kb.p1.api.Dto;
import com.acme.kb.p1.audit.AuditService;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.auth.WorkspaceRole;
import com.acme.kb.p1.authz.AclDecision;
import com.acme.kb.p1.authz.AuthorizationService;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.common.Hashing;
import com.acme.kb.p1.config.KbProperties;
import com.acme.kb.p1.document.UploadValidator;
import com.acme.kb.p1.repo.MemberMapper;
import com.acme.kb.p1.repo.PipelineCommandMapper;
import com.acme.kb.p1.repo.WikiQueryMapper;
import com.acme.kb.p1.repo.WorkspaceMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wiki 治理编排（P2-W1）：授权、幂等预检、草稿可见性判定在这里，写只在 {@link WikiWriteTx} 里。
 *
 * 三条本轮边界（`p1/contracts/state-machine-wiki.md` §6）：
 * - 不发任何引擎请求：`wiki_sync_task` 由审核事务写入后停在 `PENDING`；
 * - 不返回不可见正文：草稿与已下线页面按可见性规则裁剪，拒绝时先落 DENY 审计；
 * - 跨租户命中按 404 响应，`CROSS_TENANT_ACCESS` 只进审计。
 */
@Service
public class WikiService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    /** 视图里出现同步任务时的固定提示：本轮消费者未接线（准入 F-1），不假装它会推进。 */
    private static final String SYNC_NOTE = "发布同步消费者未接线（准入 F-1），任务停在 PENDING";

    private final AuthorizationService authorization;
    private final WikiQueryMapper queries;
    private final WikiWriteTx write;
    private final WikiCapabilities capabilities;
    private final WikiValidator validator;
    private final WikiSourceGuard sourceGuard;
    private final AuditService audit;
    private final PipelineCommandMapper pipeline;
    private final MemberMapper members;
    private final WorkspaceMapper workspaces;
    private final KbProperties props;

    public WikiService(AuthorizationService authorization, WikiQueryMapper queries, WikiWriteTx write,
                       WikiCapabilities capabilities, WikiValidator validator, WikiSourceGuard sourceGuard,
                       AuditService audit, PipelineCommandMapper pipeline, MemberMapper members,
                       WorkspaceMapper workspaces, KbProperties props) {
        this.authorization = authorization;
        this.queries = queries;
        this.write = write;
        this.capabilities = capabilities;
        this.validator = validator;
        this.sourceGuard = sourceGuard;
        this.audit = audit;
        this.pipeline = pipeline;
        this.members = members;
        this.workspaces = workspaces;
        this.props = props;
    }

    // ------------------------------------------------------------------ 读

    public Dto.WikiPageList list(Actor actor, String kbId, String status, String freshness, String owner,
                                 String cursor, Integer limit) {
        AclDecision kb = authorization.requireKbPermission(actor, kbId, "kb:read");
        int size = clamp(limit);
        List<WikiQueryMapper.PageRow> rows = queries.listPages(kb.tenantId(), kbId,
                pageStatusOrNull(status), freshnessOrNull(freshness), owner, cursor, size + 1);
        boolean more = rows.size() > size;
        List<WikiQueryMapper.PageRow> kept = more ? rows.subList(0, size) : rows;
        boolean assigned = !kept.isEmpty() && capabilities.assigned(actor, kept.get(0));
        List<Dto.WikiPageView> views = new ArrayList<>();
        for (WikiQueryMapper.PageRow row : kept) {
            // 列表不含正文与来源：capability 里的草稿位按"是否有读草稿的资格"给，前端据此置灰
            views.add(render(actor, kb, row, capabilities.compute(actor, kb, row, null, assigned), null,
                    false));
        }
        audit.recordBestEffort(actor, "wiki.page.list", "knowledge_base", kbId, "ALLOW", null,
                Map.of("count", views.size()));
        return new Dto.WikiPageList(views, more ? kept.get(kept.size() - 1).id() : null);
    }

    public Dto.WikiPageView detail(Actor actor, String pageId) {
        WikiQueryMapper.PageRow page = pageOr404(actor, pageId);
        AclDecision kb = authorization.requireKbPermission(actor, page.kbId(), "kb:read");
        WikiQueryMapper.RevisionRow current = queries.findRevisionByNo(page.id(), page.currentRevisionNo());
        WikiCapabilities.Grants grants = capabilities.compute(actor, kb, page, current);
        boolean hiddenDraft = current != null && !isPublic(current.status()) && !grants.readDraft();
        WikiQueryMapper.RevisionRow target = hiddenDraft ? published(page) : current;
        boolean bodyVisible = !offline(page) && target != null && (isPublic(target.status())
                || grants.readDraft());
        // 安全敏感读：先落审计再返回内容，审计写不进就不给正文
        audit.recordIndependent(actor, "wiki.revision.read", "wiki_revision",
                target == null ? page.id() : target.id(), hiddenDraft ? "DENY" : "ALLOW",
                hiddenDraft ? ErrorCode.ACCESS_DENIED.name() : null,
                Map.of("pageId", page.id(), "bodyReturned", bodyVisible));
        return render(actor, kb, page, grants, target, bodyVisible);
    }

    public Dto.WikiRevisionList revisions(Actor actor, String pageId, String cursor, Integer limit) {
        WikiQueryMapper.PageRow page = pageOr404(actor, pageId);
        AclDecision kb = authorization.requireKbPermission(actor, page.kbId(), "kb:read");
        boolean assigned = capabilities.assigned(actor, page);
        int size = clamp(limit);
        List<WikiQueryMapper.RevisionRow> rows = queries.listRevisions(page.id(),
                cursor == null || cursor.isBlank() ? null : intOrInvalid(cursor, "cursor"), size + 1);
        boolean more = rows.size() > size;
        List<Dto.WikiRevisionSummaryView> items = new ArrayList<>();
        for (WikiQueryMapper.RevisionRow row : (more ? rows.subList(0, size) : rows)) {
            // 未发布的修订只对作者、被指派审核人与空间管理员出现在历史里
            if (isPublic(row.status()) || capabilities.compute(actor, kb, page, row, assigned).readDraft()) {
                items.add(summary(row));
            }
        }
        audit.recordBestEffort(actor, "wiki.page.read", "wiki_page", page.id(), "ALLOW", null,
                Map.of("revisions", items.size()));
        // 游标取"本次读到的最后一行"而不是最后一条可见行，否则过滤会把翻页卡在同一页
        return new Dto.WikiRevisionList(items,
                more ? String.valueOf(rows.get(size - 1).revisionNo()) : null);
    }

    public Dto.WikiRevisionView revision(Actor actor, String pageId, int revisionNo) {
        WikiQueryMapper.PageRow page = pageOr404(actor, pageId);
        AclDecision kb = authorization.requireKbPermission(actor, page.kbId(), "kb:read");
        WikiQueryMapper.RevisionRow row = requireRevision(page, revisionNo);
        WikiCapabilities.Grants grants = capabilities.compute(actor, kb, page, row);
        boolean bodyVisible = !offline(page) && visibleBody(row, grants);
        if (!bodyVisible) {
            audit.recordIndependent(actor, "wiki.revision.read", "wiki_revision", row.id(), "DENY",
                    ErrorCode.ACCESS_DENIED.name(), Map.of("pageId", page.id()));
            throw new ApiException(ErrorCode.ACCESS_DENIED, "该修订尚未发布且不在你的可见范围内");
        }
        audit.recordIndependent(actor, "wiki.revision.read", "wiki_revision", row.id(), "ALLOW", null,
                Map.of("pageId", page.id(), "bodyReturned", true));
        return new Dto.WikiRevisionView(row.id(), row.pageId(), row.revisionNo(), row.status(),
                row.authorUserId(), row.reviewerUserId(), row.reviewedAt(), row.bodyDigest(), row.summary(),
                row.createdAt(), row.body(), row.parentRevisionId(), sourceViews(actor, row.id()));
    }

    public Dto.WikiDiffView diff(Actor actor, String pageId, int fromNo, int toNo) {
        WikiQueryMapper.PageRow page = pageOr404(actor, pageId);
        AclDecision kb = authorization.requireKbPermission(actor, page.kbId(), "kb:read");
        WikiQueryMapper.RevisionRow from = requireRevision(page, fromNo);
        WikiQueryMapper.RevisionRow to = requireRevision(page, toNo);
        boolean assigned = capabilities.assigned(actor, page);
        boolean visible = !offline(page)
                && visibleBody(from, capabilities.compute(actor, kb, page, from, assigned))
                && visibleBody(to, capabilities.compute(actor, kb, page, to, assigned));
        if (!visible) {
            audit.recordIndependent(actor, "wiki.revision.read", "wiki_page", page.id(), "DENY",
                    ErrorCode.ACCESS_DENIED.name(), Map.of("from", fromNo, "to", toNo));
            throw new ApiException(ErrorCode.ACCESS_DENIED, "差异涉及不可见的草稿，拒绝返回行级内容");
        }
        audit.recordBestEffort(actor, "wiki.revision.read", "wiki_page", page.id(), "ALLOW", null,
                Map.of("from", fromNo, "to", toNo, "digestOnly", false));
        WikiDiff.Result result = WikiDiff.of(from.body(), to.body());
        List<Dto.WikiDiffLine> lines = new ArrayList<>();
        result.lines().forEach(line -> lines.add(new Dto.WikiDiffLine(line.op(), line.text())));
        return new Dto.WikiDiffView(page.id(), fromNo, toNo, from.bodyDigest(), to.bodyDigest(),
                result.addedLines(), result.removedLines(), result.truncated(), lines);
    }

    public Dto.WikiReviewQueue reviewQueue(Actor actor, String workspaceId, String cursor, Integer limit) {
        WorkspaceMapper.Row workspace = workspaces.findById(workspaceId);
        if (workspace == null || !workspace.tenantId().equals(actor.tenantId())) {
            throw ApiException.notFound("工作空间");
        }
        MemberMapper.Row membership = members.find(workspaceId, actor.userId());
        if (membership == null || !WorkspaceRole.parse(membership.role()).allows("workspace:read")) {
            audit.deny(actor, "wiki.review.queue", "workspace", workspaceId,
                    ErrorCode.ACCESS_DENIED.name());
            throw new ApiException(ErrorCode.ACCESS_DENIED, "不是该工作空间成员");
        }
        if (!privileged(membership) && queries.reviewAssignedInWorkspace(actor.tenantId(), actor.userId(),
                workspaceId) == 0) {
            audit.deny(actor, "wiki.review.queue", "workspace", workspaceId,
                    ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED.name());
            throw new ApiException(ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED, "当前身份未被指派为审核人");
        }
        int size = clamp(limit);
        List<WikiQueryMapper.QueueRow> rows = queries.reviewQueue(actor.tenantId(), workspaceId, cursor,
                size + 1);
        boolean more = rows.size() > size;
        List<WikiQueryMapper.QueueRow> kept = more ? rows.subList(0, size) : rows;
        List<Dto.WikiReviewQueueItem> items = new ArrayList<>();
        Instant now = Instant.now();
        for (WikiQueryMapper.QueueRow row : kept) {
            Instant due = row.submittedAt().plus(props.wiki().reviewSlaHours(), ChronoUnit.HOURS);
            items.add(new Dto.WikiReviewQueueItem(row.revisionId(), row.pageId(), row.title(),
                    row.revisionNo(), row.authorUserId(), row.submittedAt(), due, now.isAfter(due)));
        }
        return new Dto.WikiReviewQueue(items, more ? cursorOf(kept.get(kept.size() - 1)) : null);
    }

    // ------------------------------------------------------------------ 写

    public Dto.WikiPageView create(Actor actor, String kbId, String idempotencyKey,
                                   Dto.WikiPageCreateRequest request) {
        AclDecision kb = authorization.requireKbPermission(actor, kbId, "kb:write");
        validator.requireSlug(request.slug());
        validator.requireTitle(request.title());
        validator.requireSummary(request.summary());
        validator.requireBody(request.body());
        String bodyDigest = Hashing.sha256Hex(request.body() == null ? "" : request.body());
        String replay = replay(actor, WikiWriteTx.SCOPE_CREATE, idempotencyKey,
                WikiWriteTx.createDigest(kbId, request.slug(), bodyDigest));
        if (replay != null) {
            return freshView(actor, replay);
        }
        WikiWriteTx.Written written = write.createPage(actor, kb.workspaceId(), kbId, request.slug(),
                request.title(), request.body(), request.summary(), request.ownerUserId(),
                request.validUntil(), request.reviewAt(), idempotencyKey);
        return freshView(actor, written.pageId());
    }

    public Dto.WikiPageView saveDraft(Actor actor, String pageId, String ifMatch,
                                      Dto.WikiDraftRequest request) {
        WikiQueryMapper.PageRow page = writablePage(actor, pageId);
        validator.requireTitle(request.title());
        validator.requireSummary(request.summary());
        validator.requireBody(request.body());
        write.saveDraft(actor, page, draftIfMatch(ifMatch, page), request.title(), request.body(),
                request.summary());
        return freshView(actor, page.id());
    }

    public Dto.WikiSourceView addSource(Actor actor, String pageId, Dto.WikiSourceRequest request) {
        WikiQueryMapper.PageRow page = writablePage(actor, pageId);
        WikiQueryMapper.RevisionRow revision = requireRevision(page, page.currentRevisionNo());
        WikiQueryMapper.SourceRow row = write.attachSource(actor, revision, request.sourceType(),
                request.sourceId(), request.sourceVersion(), request.chunkIndex(), request.locator());
        return sourceView(row, true);
    }

    public void removeSource(Actor actor, String pageId, String sourceId) {
        WikiQueryMapper.PageRow page = writablePage(actor, pageId);
        write.detachSource(actor, requireRevision(page, page.currentRevisionNo()), sourceId);
    }

    public Dto.WikiPageView submit(Actor actor, String pageId, String ifMatch, String idempotencyKey) {
        WikiQueryMapper.PageRow page = writablePage(actor, pageId);
        int expected = draftIfMatch(ifMatch, page);
        String replay = replay(actor, WikiWriteTx.SCOPE_SUBMIT, idempotencyKey,
                WikiWriteTx.submitDigest(page.id(), expected));
        if (replay != null) {
            return freshView(actor, page.id());
        }
        write.submit(actor, page, expected, idempotencyKey);
        return freshView(actor, page.id());
    }

    public Dto.WikiPageView approve(Actor actor, String revisionId, String idempotencyKey) {
        Target target = reviewTarget(actor, revisionId);
        WikiQueryMapper.PageRow page = target.page();
        WikiQueryMapper.RevisionRow revision = target.revision();
        WikiCapabilities.Grants grants = grantsOf(actor, page, revision);
        if (!grants.review()) {
            audit.denyStrict(actor, "wiki.approve", "wiki_page", page.id(),
                    ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED.name());
            capabilities.requireReview(grants);
        }
        if (replay(actor, WikiWriteTx.SCOPE_APPROVE, idempotencyKey,
                WikiWriteTx.approveDigest(revision.id())) != null) {
            return freshView(actor, page.id());
        }
        if (isAlreadyPublished(page, revision)) {
            // 用例 6：重复审核同一修订必须幂等，不产生新修订也不重复写同步任务
            audit.recordIndependent(actor, "wiki.approve", "wiki_page", page.id(), "ALLOW", null,
                    Map.of("revisionNo", revision.revisionNo(), "reason", "idempotent-replay"));
            return freshView(actor, page.id());
        }
        write.approve(actor, page, revision, grants, idempotencyKey);
        return freshView(actor, page.id());
    }

    public Dto.WikiPageView reject(Actor actor, String revisionId, String idempotencyKey,
                                   String decisionNote) {
        validator.requireDecisionNote(decisionNote);
        Target target = reviewTarget(actor, revisionId);
        WikiQueryMapper.PageRow page = target.page();
        WikiQueryMapper.RevisionRow revision = target.revision();
        if (replay(actor, WikiWriteTx.SCOPE_REJECT, idempotencyKey,
                WikiWriteTx.rejectDigest(revision.id())) != null) {
            return freshView(actor, page.id());
        }
        write.reject(actor, page, revision, grantsOf(actor, page, revision), decisionNote, idempotencyKey);
        return freshView(actor, page.id());
    }

    public Dto.WikiPageView retract(Actor actor, String pageId, String idempotencyKey) {
        WikiQueryMapper.PageRow page = writablePage(actor, pageId);
        WikiCapabilities.Grants grants = grantsOf(actor, page, null);
        capabilities.requireRetract(grants);
        int target = page.publishedRevisionNo() == null ? page.currentRevisionNo()
                : page.publishedRevisionNo();
        if (replay(actor, WikiWriteTx.SCOPE_RETRACT, idempotencyKey,
                WikiWriteTx.retractDigest(page.id(), target)) != null) {
            return freshView(actor, page.id());
        }
        write.retract(actor, page, grants, idempotencyKey);
        return freshView(actor, page.id());
    }

    public Dto.WikiPageView archive(Actor actor, String pageId, String idempotencyKey) {
        WikiQueryMapper.PageRow page = pageOr404(actor, pageId);
        WikiCapabilities.Grants grants = grantsOf(actor, page, null);
        capabilities.requireRetract(grants);
        if (replay(actor, WikiWriteTx.SCOPE_ARCHIVE, idempotencyKey,
                WikiWriteTx.archiveDigest(page.id())) != null) {
            return freshView(actor, page.id());
        }
        write.archive(actor, page, grants, idempotencyKey);
        return freshView(actor, page.id());
    }

    /**
     * 治理位 PATCH。请求体按原样收（`Map`）是因为 PATCH 的"不改"与"显式清空"必须靠**键是否存在**区分，
     * record 反序列化后两者都是 `null`，会把清空误读成不改。
     */
    public Dto.WikiPageView governance(Actor actor, String pageId, String ifMatch,
                                       Map<String, Object> body) {
        WikiQueryMapper.PageRow page = pageOr404(actor, pageId);
        capabilities.requireGovern(grantsOf(actor, page, null));
        if (body == null || body.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "治理位更新至少要给一个字段");
        }
        String owner = text(body, "ownerUserId");
        Instant validUntil = instant(body, "validUntil");
        Instant reviewAt = instant(body, "reviewAt");
        write.governance(actor, page, aclIfMatch(ifMatch), owner, validUntil,
                body.containsKey("validUntil") && validUntil == null, reviewAt,
                body.containsKey("reviewAt") && reviewAt == null);
        return freshView(actor, page.id());
    }

    // ------------------------------------------------------------------ 内部

    private WikiQueryMapper.PageRow pageOr404(Actor actor, String pageId) {
        WikiQueryMapper.PageRow page = queries.findPage(pageId);
        if (page == null) {
            throw ApiException.notFound("页面");
        }
        if (!page.tenantId().equals(actor.tenantId())) {
            // 跨租户只写审计、响应一律 404（AGENTS.md §6），用例 9
            audit.deny(actor, "wiki.page.read", "wiki_page", pageId,
                    ErrorCode.CROSS_TENANT_ACCESS.name());
            throw ApiException.notFound("页面");
        }
        return page;
    }

    private WikiQueryMapper.PageRow pageOfRevision(Actor actor, String revisionId) {
        WikiQueryMapper.RevisionRow row = queries.findRevision(revisionId);
        if (row == null) {
            throw ApiException.notFound("修订");
        }
        return pageOr404(actor, row.pageId());
    }

    /** 审核对象只能是页面的当前修订：指向历史修订按不存在处理，避免"审核旧版本"绕过 R4。 */
    private Target reviewTarget(Actor actor, String revisionId) {
        WikiQueryMapper.PageRow page = pageOfRevision(actor, revisionId);
        WikiQueryMapper.RevisionRow row = requireRevision(page, page.currentRevisionNo());
        if (!row.id().equals(revisionId)) {
            throw ApiException.notFound("修订");
        }
        return new Target(page, row);
    }

    private record Target(WikiQueryMapper.PageRow page, WikiQueryMapper.RevisionRow revision) {
    }

    /** 写操作门槛：kb:write + 页面不处于下线流程（下线中的页面不接受编辑与提交）。 */
    private WikiQueryMapper.PageRow writablePage(Actor actor, String pageId) {
        WikiQueryMapper.PageRow page = pageOr404(actor, pageId);
        AclDecision kb = authorization.requireKbPermission(actor, page.kbId(), "kb:write");
        capabilities.requireWrite(capabilities.compute(actor, kb, page, null));
        if (offline(page)) {
            audit.deny(actor, "wiki.revision.save", "wiki_page", page.id(),
                    ErrorCode.INVALID_STATE_TRANSITION.name());
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "页面已进入下线流程，不接受编辑");
        }
        return page;
    }

    private WikiCapabilities.Grants grantsOf(Actor actor, WikiQueryMapper.PageRow page,
                                             WikiQueryMapper.RevisionRow revision) {
        AclDecision kb = authorization.requireKbPermission(actor, page.kbId(), "kb:read");
        return capabilities.compute(actor, kb, page, revision);
    }

    /** 写响应复用视图渲染，但不补读取审计（写侧已有各自的 ALLOW）。 */
    private Dto.WikiPageView freshView(Actor actor, String pageId) {
        WikiQueryMapper.PageRow page = pageOr404(actor, pageId);
        AclDecision kb = authorization.requireKbPermission(actor, page.kbId(), "kb:read");
        WikiQueryMapper.RevisionRow current = queries.findRevisionByNo(page.id(), page.currentRevisionNo());
        WikiCapabilities.Grants grants = capabilities.compute(actor, kb, page, current);
        boolean bodyVisible = current != null && !offline(page) && visibleBody(current, grants);
        return render(actor, kb, page, grants, current, bodyVisible);
    }

    private Dto.WikiPageView render(Actor actor, AclDecision kb, WikiQueryMapper.PageRow page,
                                    WikiCapabilities.Grants grants, WikiQueryMapper.RevisionRow target,
                                    boolean bodyVisible) {
        if (target == null) {
            // 列表项不逐行回查来源与同步任务（N+1），只给元数据与置灰用的 capability
            return new Dto.WikiPageView(page.id(), page.workspaceId(), page.kbId(), page.slug(),
                    page.title(), page.status(), page.freshness(), page.ownerUserId(),
                    page.currentRevisionNo(), page.publishedRevisionNo(), page.aclVersion(),
                    page.validUntil(), page.reviewAt(), page.createdAt(), page.updatedAt(), null, null,
                    grants.names(), List.of(), null);
        }
        return new Dto.WikiPageView(page.id(), page.workspaceId(), page.kbId(), page.slug(), page.title(),
                page.status(), page.freshness(), page.ownerUserId(), page.currentRevisionNo(),
                page.publishedRevisionNo(), page.aclVersion(), page.validUntil(), page.reviewAt(),
                page.createdAt(), page.updatedAt(), bodyVisible ? target.body() : null,
                target.bodyDigest(), grants.names(), sourceViews(actor, target.id()),
                taskView(queries.latestTask(page.id())));
    }

    private List<Dto.WikiSourceView> sourceViews(Actor actor, String revisionId) {
        List<Dto.WikiSourceView> out = new ArrayList<>();
        for (WikiQueryMapper.SourceRow row : queries.listSources(revisionId)) {
            out.add(sourceView(row, sourceGuard.available(actor, row)));
        }
        return out;
    }

    private Dto.WikiSourceView sourceView(WikiQueryMapper.SourceRow row, boolean available) {
        return new Dto.WikiSourceView(row.id(), row.revisionId(), row.sourceType(), row.sourceId(),
                row.sourceVersion(), row.chunkIndex(), row.locator(), row.contentDigest(), row.aclVersion(),
                available);
    }

    private Dto.WikiSyncTaskView taskView(WikiQueryMapper.TaskRow row) {
        if (row == null) {
            return null;
        }
        return new Dto.WikiSyncTaskView(row.id(), row.operation(), row.status(), row.attempts(),
                row.maxAttempts(), row.nextRunAt(), row.errorCode(), SYNC_NOTE);
    }

    private Dto.WikiRevisionSummaryView summary(WikiQueryMapper.RevisionRow row) {
        return new Dto.WikiRevisionSummaryView(row.id(), row.pageId(), row.revisionNo(), row.status(),
                row.authorUserId(), row.reviewerUserId(), row.reviewedAt(), row.bodyDigest(), row.summary(),
                row.createdAt());
    }

    private WikiQueryMapper.RevisionRow published(WikiQueryMapper.PageRow page) {
        return page.publishedRevisionId() == null ? null : queries.findRevision(page.publishedRevisionId());
    }

    private WikiQueryMapper.RevisionRow requireRevision(WikiQueryMapper.PageRow page, int revisionNo) {
        WikiQueryMapper.RevisionRow row = queries.findRevisionByNo(page.id(), revisionNo);
        if (row == null) {
            throw ApiException.notFound("修订");
        }
        return row;
    }

    /**
     * 修订正文可见性（D-03 草稿不可见）：已生效轴（APPROVED/SUPERSEDED）对库内读者开放，
     * 未发布与被否决内容只有作者、被指派审核人与空间管理员可见。
     */
    private boolean visibleBody(WikiQueryMapper.RevisionRow row, WikiCapabilities.Grants grants) {
        return isPublic(row.status()) || grants.readDraft();
    }

    private static boolean isPublic(String revisionStatus) {
        WikiRevisionStatus status = WikiRevisionStatus.parse(revisionStatus);
        return status == WikiRevisionStatus.APPROVED || status == WikiRevisionStatus.SUPERSEDED;
    }

    private static boolean offline(WikiQueryMapper.PageRow page) {
        return WikiPageStatus.parse(page.status()).bodyDenied();
    }

    private static boolean privileged(MemberMapper.Row membership) {
        WorkspaceRole role = WorkspaceRole.parse(membership.role());
        return role == WorkspaceRole.OWNER || role == WorkspaceRole.ADMIN;
    }

    private static boolean isAlreadyPublished(WikiQueryMapper.PageRow page,
                                              WikiQueryMapper.RevisionRow revision) {
        return revision.id().equals(page.publishedRevisionId())
                && WikiPageStatus.PUBLISHED.name().equals(page.status())
                && WikiRevisionStatus.APPROVED.name().equals(revision.status());
    }

    /** 幂等预检：同键同内容回已有资源，同键不同内容 409；返回已存在的资源 id。 */
    private String replay(Actor actor, String scope, String key, String contentDigest) {
        if (key == null || key.isBlank()) {
            return null;
        }
        UploadValidator.requireIdempotencyKey(key);
        String recorded = pipeline.findIdempotentDigest(actor.tenantId(), actor.userId(), scope, key);
        if (recorded == null) {
            return null;
        }
        if (!recorded.equals(Hashing.sha256Hex(contentDigest))) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT, "同一幂等键携带了不同内容");
        }
        return pipeline.findIdempotentResource(actor.tenantId(), actor.userId(), scope, key);
    }

    /** If-Match 允许强 ETag（`"v{acl}-{actorHash}-{revisionNo}"`）或裸修订号。 */
    private static int draftIfMatch(String raw, WikiQueryMapper.PageRow page) {
        String value = stripETag(raw);
        if (!value.startsWith("v")) {
            return intOrInvalid(value, "If-Match");
        }
        String[] parts = value.split("-");
        try {
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "If-Match 需为当前修订号（页面现为 " + page.currentRevisionNo() + "）");
        }
    }

    /** 治理位的 If-Match 打在 `wiki_page.acl_version` 上（VERSION_CONFLICT 的产生点）。 */
    private static long aclIfMatch(String raw) {
        String value = stripETag(raw);
        if (value.startsWith("v")) {
            value = value.substring(1).split("-")[0];
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "If-Match 需为页面治理位版本号");
        }
    }

    private static String stripETag(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "缺少 If-Match");
        }
        String value = raw.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static String text(Map<String, Object> body, String key) {
        if (body == null || !body.containsKey(key)) {
            return null;
        }
        Object value = body.get(key);
        if (value == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, key + " 不能为空");
        }
        String raw = String.valueOf(value).strip();
        if (raw.isEmpty() || raw.length() > 64) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, key + " 长度不合法");
        }
        return raw;
    }

    private static Instant instant(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        try {
            return Instant.parse(String.valueOf(body.get(key)).strip());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, key + " 需为 ISO-8601 UTC 时间");
        }
    }

    private static int intOrInvalid(String raw, String name) {
        try {
            return Integer.parseInt(raw.strip());
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, name + " 必须是数字");
        }
    }

    private static int clamp(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "limit 需在 1–" + MAX_LIMIT + " 之间");
        }
        return limit;
    }

    private static String pageStatusOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.strip().toUpperCase();
        try {
            return WikiPageStatus.parse(value).name();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "未知的页面状态 " + raw);
        }
    }

    private static String freshnessOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return WikiFreshness.parse(raw).name();
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "未知的新鲜度 " + raw);
        }
    }

    private static String cursorOf(WikiQueryMapper.QueueRow row) {
        return row.submittedAt().toEpochMilli() + ":" + row.revisionId();
    }
}
