package com.acme.kb.p1.it;

import com.acme.kb.p1.api.Dto;
import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.Classification;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.common.Hashing;
import com.acme.kb.p1.wiki.WikiWriteTx;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wiki 治理与状态轴集成测试（契约 `state-machine-wiki.md` §5 用例 1/2/9 的 W1 部分）。
 *
 * 全部走真实 MySQL + 真状态机，**不发引擎请求**：断言只到"同步任务落库并保持 `PENDING`"为止，
 * 消费者接线属 P2-W2（§1.3.1 阻断级地基 F-1）。每个用例一套独立租户边界，计数按 tenant_id 精确。
 */
class WikiGovernanceIntegrationTest extends P1WikiTestContext {

    @Test
    void publishHappyPathMovesPointerAndLeavesSyncTaskPending() {
        Space s = space();
        Dto.WikiPageView draft = draftPageWithSource(s, "第一段\n第二段");
        assertEquals("DRAFT", draft.status());
        assertEquals(1, draft.currentRevisionNo());
        assertTrue(draft.capabilities().contains("wiki:submit"));
        assertEquals(1, draft.sources().size());
        assertTrue(draft.sources().get(0).available());
        assertEquals(1, auditCount(s.tenantId(), "wiki.page.create", "ALLOW"));

        Dto.WikiPageView pending = wiki.submit(s.author(), draft.id(), "1", key());
        assertEquals("IN_REVIEW", pending.status());
        assertEquals("PENDING", revisionStatus(draft.id(), 1));

        Dto.WikiPageView published = wiki.approve(s.reviewer(), currentRevisionId(draft.id()), key());
        assertEquals("PUBLISHED", published.status());
        assertEquals(1, published.publishedRevisionNo());
        assertEquals("第一段\n第二段", published.body());
        assertNotNull(published.syncTask());
        assertEquals("PUBLISH", published.syncTask().operation());

        String reviewer = jdbc.queryForObject("SELECT reviewer_user_id FROM wiki_revision WHERE id = ?",
                String.class, currentRevisionId(draft.id()));
        assertEquals(s.reviewerId(), reviewer, "审核人与指针移动必须同事务落库（契约 §1）");

        // SYNC 轴本轮只有边表：任务行落在 PENDING，且不产生任何 kb.wiki 事件（消费者在 W2）
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ? AND operation = ?"
                + " AND status = 'PENDING'", draft.id(), WikiWriteTx.OP_PUBLISH));
        assertEquals(0, count("SELECT COUNT(*) FROM outbox_event WHERE tenant_id = ?"
                + " AND topic LIKE 'kb.wiki%'", s.tenantId()));
        assertEquals(1, auditCount(s.tenantId(), "wiki.approve", "ALLOW"));
        assertEquals(1, auditCount(s.tenantId(), "wiki.submit", "ALLOW"));
        assertEquals(0, auditCount(s.tenantId(), "wiki.approve", "DENY"));
    }

    /** 用例 1：对 `DRAFT` 页面执行 approve → 409，且拒绝审计不能随业务事务回滚。 */
    @Test
    void approvingADraftPageIsRejectedAndAudited() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "只有草稿");
        String revisionId = currentRevisionId(page.id());

        ApiException error = assertThrows(ApiException.class,
                () -> wiki.approve(s.reviewer(), revisionId, key()));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, error.code());
        assertEquals("DRAFT", pageStatus(page.id()));
        assertEquals("DRAFT", revisionStatus(page.id(), 1));
        assertNull(jdbc.queryForObject("SELECT reviewer_user_id FROM wiki_revision WHERE id = ?",
                String.class, revisionId), "被拒的审核不得留下半笔写入");
        assertEquals(1, auditCount(s.tenantId(), "wiki.approve", "DENY"));
        assertEquals(0, auditCount(s.tenantId(), "wiki.approve", "ALLOW"));
        assertEquals(0, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ?", page.id()));
    }

    /** 用例 2：作者同时是被指派审核人时，自批由 E3 前置校验挡住，除审计外无副作用。 */
    @Test
    void authorCannotApproveOwnRevisionEvenWhenAssigned() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "自批用例");
        assignReviewer(s, s.authorId());
        wiki.submit(s.author(), page.id(), "1", key());

        ApiException error = assertThrows(ApiException.class,
                () -> wiki.approve(s.author(), currentRevisionId(page.id()), key()));
        assertEquals(ErrorCode.WIKI_REVIEW_SELF_APPROVAL, error.code());
        assertEquals("IN_REVIEW", pageStatus(page.id()));
        assertEquals("PENDING", revisionStatus(page.id(), 1));
        assertEquals(1, auditCount(s.tenantId(), "wiki.approve", "DENY"));
        assertEquals(0, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ?", page.id()));
        assertEquals(0, count("SELECT COUNT(*) FROM idempotency_record WHERE tenant_id = ? AND scope = ?",
                s.tenantId(), WikiWriteTx.SCOPE_APPROVE));
    }

    /** D-03：未发布正文只对作者、被指派审核人与空间管理员开放。 */
    @Test
    void pendingDraftIsInvisibleToPlainReader() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "未发布的结论");
        wiki.submit(s.author(), page.id(), "1", key());

        Dto.WikiPageView seen = wiki.detail(s.viewer(), page.id());
        assertNull(seen.body(), "草稿正文不得出现在响应里");
        assertNull(seen.bodyDigest());
        assertFalse(seen.capabilities().contains("wiki:read-draft"));
        assertEquals(1, auditCount(s.tenantId(), "wiki.revision.read", "DENY"));
        assertEquals(0, metadataHits(s.tenantId(), "未发布的结论"));

        ApiException error = assertThrows(ApiException.class, () -> wiki.revision(s.viewer(), page.id(), 1));
        assertEquals(ErrorCode.ACCESS_DENIED, error.code());
        assertEquals(2, auditCount(s.tenantId(), "wiki.revision.read", "DENY"));
    }

    /**
     * D-05 正反两半边：审核能力来自 `wiki_review_assignment` 行，删掉行后同一身份立刻失去
     * 读草稿与审核的资格——不靠角色，也不靠进程内标记。
     */
    @Test
    void reviewCapabilityComesFromTheAssignmentRow() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "待审正文");
        wiki.submit(s.author(), page.id(), "1", key());

        Dto.WikiPageView asReviewer = wiki.detail(s.reviewer(), page.id());
        assertEquals("待审正文", asReviewer.body(), "被指派的 VIEWER 也要能读到待审正文");
        assertTrue(asReviewer.capabilities().contains("wiki:review"));
        assertTrue(asReviewer.capabilities().contains("wiki:read-draft"));
        assertFalse(asReviewer.capabilities().contains("wiki:write"));

        jdbc.update("DELETE FROM wiki_review_assignment WHERE tenant_id = ? AND user_id = ?",
                s.tenantId(), s.reviewerId());
        assertNull(wiki.detail(s.reviewer(), page.id()).body());
        ApiException error = assertThrows(ApiException.class, () -> wiki.approve(s.reviewer(),
                currentRevisionId(page.id()), key()));
        assertEquals(ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED, error.code());
    }

    /** E2 + R3：退回只落摘要哈希，理由原文进站内通知与响应，绝不落库、不落审计（红线 1）。 */
    @Test
    void rejectKeepsOnlyTheDigestOfTheDecisionNote() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "要退回的正文");
        String revisionId = currentRevisionId(page.id());
        wiki.submit(s.author(), page.id(), "1", key());

        wiki.reject(s.reviewer(), revisionId, key(), "口径不对，请补充来源");
        assertEquals("DRAFT", pageStatus(page.id()));
        assertEquals("REJECTED", revisionStatus(page.id(), 1));
        assertEquals(s.reviewerId(), jdbc.queryForObject(
                "SELECT reviewer_user_id FROM wiki_revision WHERE id = ?", String.class, revisionId));
        assertEquals(Hashing.sha256Hex("口径不对，请补充来源"), jdbc.queryForObject(
                "SELECT decision_note_digest FROM wiki_revision WHERE id = ?", String.class, revisionId));
        assertEquals(0, metadataHits(s.tenantId(), "口径不对"));
        assertEquals(1, auditCount(s.tenantId(), "wiki.reject", "ALLOW"));

        // REJECTED 是终态（§3）：退回后保存等于开新修订，旧行留作历史与差异计算
        Dto.WikiPageView reopened = wiki.saveDraft(s.author(), page.id(), "1",
                new Dto.WikiDraftRequest("治理页面", "改后的正文", null));
        assertEquals(2, reopened.currentRevisionNo());
        assertEquals("REJECTED", revisionStatus(page.id(), 1));
        assertEquals("DRAFT", revisionStatus(page.id(), 2));
    }

    /** §3：审核中（`PENDING`）的修订不接受原地改动。 */
    @Test
    void pendingRevisionRejectsInPlaceEdits() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "审核中不可改");
        wiki.submit(s.author(), page.id(), "1", key());

        ApiException error = assertThrows(ApiException.class, () -> wiki.saveDraft(s.author(), page.id(), "1",
                new Dto.WikiDraftRequest("治理页面", "偷偷改正文", null)));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, error.code());
        assertEquals("审核中不可改", jdbc.queryForObject(
                "SELECT body FROM wiki_revision WHERE page_id = ? AND revision_no = 1", String.class,
                page.id()));
        assertEquals(1, auditCount(s.tenantId(), "wiki.revision.save", "DENY"));
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_revision WHERE page_id = ?", page.id()));
    }

    /** 治理位：每次改动递增 `acl_version`，且"清空"与"不改"必须可区分。 */
    @Test
    void governanceChangesBumpTheAclVersionOneByOne() {
        Space s = space();
        Dto.WikiPageView page = draftPage(s, "治理位用例");
        assertEquals(0L, page.aclVersionGuard());

        Dto.WikiPageView validity = wiki.governance(s.author(), page.id(), "0",
                Map.of("validUntil", "2027-06-01T00:00:00Z"));
        assertEquals(1L, validity.aclVersionGuard());
        assertEquals(Instant.parse("2027-06-01T00:00:00Z"), validity.validUntil());
        assertEquals(1, auditCount(s.tenantId(), "wiki.validity.update", "ALLOW"));
        assertEquals(0, auditCount(s.tenantId(), "wiki.owner.change", "ALLOW"));

        Map<String, Object> clear = new HashMap<>();
        clear.put("validUntil", null);
        Dto.WikiPageView cleared = wiki.governance(s.author(), page.id(), "1", clear);
        assertNull(cleared.validUntil(), "键存在且值为 null 是显式清空，不是不改");
        assertEquals(2L, cleared.aclVersionGuard());

        Dto.WikiPageView owner = wiki.governance(s.author(), page.id(), "2",
                Map.of("ownerUserId", s.reviewerId()));
        assertEquals(s.reviewerId(), owner.ownerUserId());
        assertEquals(3L, owner.aclVersionGuard());
        assertEquals(1, auditCount(s.tenantId(), "wiki.owner.change", "ALLOW"));

        // 让他人当负责人后，原创建者不再有治理位权限（govern = 空间 OWNER/ADMIN 或页面负责人）
        ApiException error = assertThrows(ApiException.class, () -> wiki.governance(s.author(), page.id(), "3",
                Map.of("reviewAt", "2026-12-01T00:00:00Z")));
        assertEquals(ErrorCode.ACCESS_DENIED, error.code());
        assertEquals(3L, jdbc.queryForObject("SELECT acl_version FROM wiki_page WHERE id = ?", Long.class,
                page.id()));
    }

    /** §3 禁止迁移：`PUBLISHED` / `RETRACTING` 都不能直接归档，必须先经 `E5`/`E6`。 */
    @Test
    void archiveFollowsThePageStateAxis() {
        Space s = space();
        Dto.WikiPageView draft = draftPage(s, "归档草稿");
        assertEquals("ARCHIVED", wiki.archive(s.author(), draft.id(), key()).status());
        assertEquals("ARCHIVED", pageStatus(draft.id()));
        assertEquals(1, auditCount(s.tenantId(), "wiki.archive", "ALLOW"));

        Dto.WikiPageView published = publishedPage(s);
        ApiException direct = assertThrows(ApiException.class,
                () -> wiki.archive(s.author(), published.id(), key()));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, direct.code());
        assertEquals("PUBLISHED", pageStatus(published.id()));

        wiki.retract(s.author(), published.id(), key());
        assertEquals("RETRACTING", pageStatus(published.id()));
        assertThrows(ApiException.class, () -> wiki.archive(s.author(), published.id(), key()));
        assertEquals("RETRACTING", pageStatus(published.id()));
        assertEquals(2, auditCount(s.tenantId(), "wiki.archive", "DENY"));
    }

    /** E5：撤稿进 `RETRACTING`、同事务写 `RETRACT` 任务，正文与编辑一律关闭（不依赖引擎是否已删索引）。 */
    @Test
    void retractHidesBodyAndBlocksEdits() {
        Space s = space();
        Dto.WikiPageView published = publishedPage(s);

        Dto.WikiPageView retracting = wiki.retract(s.author(), published.id(), key());
        assertEquals("RETRACTING", retracting.status());
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ? AND operation = ?"
                + " AND status = 'PENDING'", published.id(), WikiWriteTx.OP_RETRACT));
        assertNull(wiki.detail(s.author(), published.id()).body());

        ApiException edit = assertThrows(ApiException.class, () -> wiki.saveDraft(s.author(),
                published.id(), "1", new Dto.WikiDraftRequest("治理页面", "下线中改正文", null)));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, edit.code());

        // W1 没有 RETRACTING 自环：重复撤稿 409，补偿入口（重新发起并生成新任务行）属 W2
        ApiException again = assertThrows(ApiException.class,
                () -> wiki.retract(s.author(), published.id(), key()));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, again.code());
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ? AND operation = ?",
                published.id(), WikiWriteTx.OP_RETRACT));
    }

    /** 用例 9：跨租户命中只写审计，响应一律 404，读侧写侧都不例外。 */
    @Test
    void crossTenantPageHitIsAlwaysFourOhFour() {
        Space mine = space();
        Space other = space();
        Dto.WikiPageView page = draftPage(other, "他人页面正文");

        ApiException read = assertThrows(ApiException.class, () -> wiki.detail(mine.author(), page.id()));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, read.code());
        assertEquals(1, count("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ? AND action = ?"
                        + " AND result = 'DENY' AND reason_code = ?", mine.tenantId(), "wiki.page.read",
                ErrorCode.CROSS_TENANT_ACCESS.name()));
        assertEquals(0, auditCount(mine.tenantId(), "wiki.page.read", "ALLOW"));

        ApiException write = assertThrows(ApiException.class,
                () -> wiki.archive(mine.author(), page.id(), key()));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, write.code());
        assertEquals(2, count("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ? AND reason_code = ?",
                mine.tenantId(), ErrorCode.CROSS_TENANT_ACCESS.name()));
        assertEquals("DRAFT", pageStatus(page.id()), "他人的页面不得被跨租户请求改动");
        assertEquals(0, count("SELECT COUNT(*) FROM wiki_page WHERE tenant_id = ? AND status <> 'DRAFT'",
                other.tenantId()));
    }

    /** `E4`/`R4`：已发布页面提新修订时页面态不动（只有 `R1`），通过才换指针并把旧修订置 `SUPERSEDED`。 */
    @Test
    void secondRevisionReplacesThePublishedPointer() {
        Space s = space();
        Dto.WikiPageView published = publishedPage(s);

        Dto.WikiPageView withDraft = wiki.saveDraft(s.author(), published.id(), "1",
                new Dto.WikiDraftRequest("治理页面", "第一段\n第二段\n第三段", "补充第三段"));
        assertEquals(2, withDraft.currentRevisionNo());
        assertEquals("PUBLISHED", pageStatus(published.id()));
        assertEquals(1, withDraft.publishedRevisionNo(), "新草稿不影响当前可见性");
        wiki.addSource(s.author(), published.id(), new Dto.WikiSourceRequest("DOCUMENT",
                readyDocument(s, s.authorId()), 1, 0, "第 2 节"));

        Dto.WikiPageView pending = wiki.submit(s.author(), published.id(), "2", key());
        assertEquals("PUBLISHED", pending.status(), "PUBLISHED 页面提修订时 PAGE 轴无边可走（契约 §2）");
        assertEquals("PENDING", revisionStatus(published.id(), 2));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ? AND action = ?"
                        + " AND result = 'ALLOW' AND metadata_json LIKE '%NONE%'", s.tenantId(), "wiki.submit"),
                "pageEdge 必须是 NONE：这条断言就是 E4/R4 可产生的证据");

        Dto.WikiPageView republished = wiki.approve(s.reviewer(), currentRevisionId(published.id()), key());
        assertEquals(2, republished.publishedRevisionNo());
        assertEquals("APPROVED", revisionStatus(published.id(), 2));
        assertEquals("SUPERSEDED", revisionStatus(published.id(), 1));
        assertEquals(2, count("SELECT COUNT(*) FROM wiki_revision WHERE page_id = ?", published.id()),
                "R4 不删旧行");
        assertEquals(2, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ? AND operation = ?",
                published.id(), WikiWriteTx.OP_PUBLISH));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ? AND action = ?"
                        + " AND metadata_json LIKE '%E4%'", s.tenantId(), "wiki.approve"));
        assertEquals(1, count("SELECT COUNT(*) FROM audit_event WHERE tenant_id = ? AND action = ?"
                        + " AND resource_type = 'wiki_revision' AND metadata_json LIKE '%R4%'", s.tenantId(),
                "wiki.approve"));
    }

    /** D-10 fail-close：没有来源（或来源全被摘下）的页面提交不了，页面与修订状态原地不动。 */
    @Test
    void submitWithoutAnySourceFailsClose() {
        Space s = space();
        Dto.WikiPageView page = draftPage(s, "无来源的结论");

        ApiException error = assertThrows(ApiException.class,
                () -> wiki.submit(s.author(), page.id(), "1", key()));
        assertEquals(ErrorCode.WIKI_SOURCE_UNAVAILABLE, error.code());
        assertEquals("DRAFT", pageStatus(page.id()));
        assertEquals("DRAFT", revisionStatus(page.id(), 1));
        assertEquals(0, auditCount(s.tenantId(), "wiki.submit", "ALLOW"));
        assertEquals(0, count("SELECT COUNT(*) FROM wiki_sync_task WHERE page_id = ?", page.id()));

        // 页面间引用属 P2-W3：连挂接都不接受，不留无法产生审计的分支
        ApiException type = assertThrows(ApiException.class, () -> wiki.addSource(s.author(), page.id(),
                new Dto.WikiSourceRequest("WIKI_PAGE", page.id(), 1, 0, null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, type.code());
        ApiException unknown = assertThrows(ApiException.class, () -> wiki.addSource(s.author(), page.id(),
                new Dto.WikiSourceRequest("URL", "https://example.invalid", 1, 0, null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, unknown.code());
    }

    /** 来源生命周期：重复挂接幂等、摘下后 404、审核中不再允许改动来源。 */
    @Test
    void sourceAttachAndDetachAreIdempotentAndDraftScoped() {
        Space s = space();
        Dto.WikiPageView page = draftPage(s, "来源生命周期");
        String documentId = readyDocument(s, s.authorId());
        Dto.WikiSourceRequest request = new Dto.WikiSourceRequest("DOCUMENT", documentId, 1, 0, "第 1 节");

        Dto.WikiSourceView attached = wiki.addSource(s.author(), page.id(), request);
        Dto.WikiSourceView again = wiki.addSource(s.author(), page.id(), request);
        assertEquals(attached.id(), again.id(), "uk_wiki_source：重复挂接不产生第二行");
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_source WHERE revision_id = ?",
                attached.revisionId()));

        wiki.removeSource(s.author(), page.id(), attached.id());
        assertEquals(0, count("SELECT COUNT(*) FROM wiki_source WHERE id = ?", attached.id()));
        ApiException missing = assertThrows(ApiException.class,
                () -> wiki.removeSource(s.author(), page.id(), attached.id()));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, missing.code());

        assertEquals(ErrorCode.WIKI_SOURCE_UNAVAILABLE, assertThrows(ApiException.class,
                () -> wiki.submit(s.author(), page.id(), "1", key())).code());

        wiki.addSource(s.author(), page.id(), request);
        wiki.submit(s.author(), page.id(), "1", key());
        ApiException duringReview = assertThrows(ApiException.class, () -> wiki.addSource(s.author(),
                page.id(), new Dto.WikiSourceRequest("DOCUMENT", readyDocument(s, s.authorId()), 1, 0, null)));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, duringReview.code());
        assertEquals(1, auditCount(s.tenantId(), "wiki.source.attach", "DENY"));
    }

    /** D-10：来源断链只把来源标成不可用，不阻断已发布内容的读取（新鲜度位由 W3 调度器写）。 */
    @Test
    void brokenSourceMarksUnavailableWithoutBlockingRead() {
        Space s = space();
        Dto.WikiPageView published = publishedPage(s);
        assertEquals("FRESH", published.freshness(), "本轮没有新鲜度调度器（W3），不得假装派生过 STALE");

        jdbc.update("UPDATE document SET status = 'DELETED', deleted_at = NOW(3) WHERE tenant_id = ?",
                s.tenantId());
        Dto.WikiPageView seen = wiki.detail(s.author(), published.id());
        assertEquals("第一段\n第二段", seen.body());
        assertEquals(1, seen.sources().size());
        assertFalse(seen.sources().get(0).available());
    }

    /** 列表与历史：列表项不带正文与来源（避免 N+1），未发布修订不进读者可见的历史。 */
    @Test
    void listAndHistoryHideUnpublishedContent() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "历史过滤");
        wiki.submit(s.author(), page.id(), "1", key());

        Dto.WikiPageList list = wiki.list(s.author(), s.kbId(), null, null, null, null, 20);
        assertEquals(1, list.items().size());
        assertNull(list.items().get(0).body());
        assertTrue(list.items().get(0).sources().isEmpty());
        assertTrue(list.items().get(0).capabilities().contains("wiki:read-draft"),
                "列表要给出置灰用的 capability，但不给内容");
        assertNull(list.nextCursor());
        assertEquals(0, wiki.list(s.author(), s.kbId(), "PUBLISHED", null, null, null, 20).items().size());

        assertEquals(0, wiki.revisions(s.viewer(), page.id(), null, 20).items().size());
        List<Dto.WikiRevisionSummaryView> mine = wiki.revisions(s.author(), page.id(), null, 20).items();
        assertEquals(1, mine.size());
        assertEquals("PENDING", mine.get(0).status());
    }

    /** 差异计算只在两个修订都可见时才出行级内容（用例 3 的读侧半边）。 */
    @Test
    void diffRequiresBothRevisionsToBeVisible() {
        Space s = space();
        Dto.WikiPageView published = publishedPage(s);
        wiki.saveDraft(s.author(), published.id(), "1",
                new Dto.WikiDraftRequest("治理页面", "第一段\n第三段", null));
        // 来源挂在修订上：新修订必须自带来源才提得交（D-10 fail-close）
        wiki.addSource(s.author(), published.id(), new Dto.WikiSourceRequest("DOCUMENT",
                readyDocument(s, s.authorId()), 1, 0, "第 2 节"));

        ApiException hidden = assertThrows(ApiException.class,
                () -> wiki.diff(s.viewer(), published.id(), 1, 2));
        assertEquals(ErrorCode.ACCESS_DENIED, hidden.code());
        assertEquals(1, auditCount(s.tenantId(), "wiki.revision.read", "DENY"));

        wiki.submit(s.author(), published.id(), "2", key());
        wiki.approve(s.reviewer(), currentRevisionId(published.id()), key());
        Dto.WikiDiffView diff = wiki.diff(s.viewer(), published.id(), 1, 2);
        assertEquals(1, diff.addedLines());
        assertEquals(1, diff.removedLines());
        assertFalse(diff.truncated());
        assertNotNull(diff.fromBodyDigest());
        assertNotNull(diff.toBodyDigest());
        assertTrue(diff.lines().stream().allMatch(line -> List.of("context", "added", "removed")
                .contains(line.op())));
    }

    /** 队列门槛（D-05）：被指派者能开队列，未指派者走专用码，非成员先被成员校验挡下。 */
    @Test
    void reviewQueueRequiresMembershipOrAssignment() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "排队待审");
        wiki.submit(s.author(), page.id(), "1", key());

        Actor outsider = actor(id(), s.tenantId());
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(ApiException.class,
                () -> wiki.reviewQueue(outsider, s.workspaceId(), null, 20)).code());

        ApiException unassigned = assertThrows(ApiException.class,
                () -> wiki.reviewQueue(s.viewer(), s.workspaceId(), null, 20));
        assertEquals(ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED, unassigned.code());
        assertEquals(2, auditCount(s.tenantId(), "wiki.review.queue", "DENY"));

        // KB 作用域的指派行必须能打开所属空间的队列（否则指派到库的人看不到待办）
        Dto.WikiReviewQueue queue = wiki.reviewQueue(s.reviewer(), s.workspaceId(), null, 20);
        assertEquals(1, queue.items().size());
        assertEquals(page.id(), queue.items().get(0).pageId());
        assertEquals(1, queue.items().get(0).revisionNo());
        assertFalse(queue.items().get(0).overdue());
        assertNull(queue.nextCursor());
    }

    /** 审核链完整性 + 红线 1：整条 Wiki 写路径的审计可验链，且元数据里搜不到正文。 */
    @Test
    void auditChainStaysValidAndCarriesNoBodyText() {
        Space s = space();
        Dto.WikiPageView page = draftPageWithSource(s, "机密正文一二三");
        wiki.submit(s.author(), page.id(), "1", key());
        Dto.WikiPageView approved = wiki.approve(s.reviewer(), currentRevisionId(page.id()), key());
        wiki.governance(s.author(), page.id(), String.valueOf(approved.aclVersionGuard()),
                Map.of("reviewAt", "2026-12-01T00:00:00Z"));
        wiki.retract(s.author(), page.id(), key());

        assertTrue(audit.verifyChain(s.tenantId()), "状态与审计同事务，链必须逐条可验");
        assertEquals(0, metadataHits(s.tenantId(), "机密正文一二三"));
        for (String action : jdbc.queryForList("SELECT DISTINCT action FROM audit_event WHERE tenant_id = ?",
                String.class, s.tenantId())) {
            assertTrue(action.matches("^[a-z][a-z0-9.]{2,63}$"),
                    "审计动作名必须匹配契约 schema：" + action);
        }
        assertTrue(auditCount(s.tenantId(), "wiki.retract", "ALLOW") >= 1);
    }

    /**
     * W1-07 的注入侧：审计写不进 → 页面与修订必须整体回滚，绝不留"有页面没审计"的半条状态。
     * 手法沿用 `P1CoreIntegrationTest.auditFailureRollsBackBusinessWrite`（null action 撞非空约束），
     * 不用 mock，也不动测试库账号权限。
     */
    @Test
    void auditWriteFailureRollsBackTheWholePageCreation() {
        Space s = space();
        String slug = "rollback-" + id().substring(0, 8);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(ApiException.class, () -> tx.executeWithoutResult(status -> {
            wiki.create(s.author(), s.kbId(), key(), new Dto.WikiPageCreateRequest(slug, "治理页面",
                    "回滚正文", "一句话摘要", null, null, null));
            audit.recordInTx(s.author(), null, "wiki_page", slug, "ALLOW", null, Map.of());
        }));

        assertEquals(0, count("SELECT COUNT(*) FROM wiki_page WHERE kb_id = ? AND slug = ?",
                s.kbId(), slug), "审计失败时页面写必须回滚");
        assertEquals(0, auditCount(s.tenantId(), "wiki.page.create", "ALLOW"),
                "同事务的 ALLOW 审计随业务一起回滚");
        assertTrue(audit.verifyChain(s.tenantId()), "失败审计不得推进链头");
    }

    /** 幂等键：同键同内容回已有资源且不再改状态，同键不同内容 409。 */
    @Test
    void idempotencyKeyReplaysAndRejectsConflictingContent() {
        Space s = space();
        String slug = "slug-" + id().substring(0, 8);
        Dto.WikiPageCreateRequest request = new Dto.WikiPageCreateRequest(slug, "幂等标题", "正文", null,
                null, null, null);
        String createKey = key();

        Dto.WikiPageView first = wiki.create(s.author(), s.kbId(), createKey, request);
        Dto.WikiPageView replay = wiki.create(s.author(), s.kbId(), createKey, request);
        assertEquals(first.id(), replay.id());
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_page WHERE kb_id = ?", s.kbId()));
        assertEquals(1, auditCount(s.tenantId(), "wiki.page.create", "ALLOW"));

        ApiException conflict = assertThrows(ApiException.class, () -> wiki.create(s.author(), s.kbId(),
                createKey, new Dto.WikiPageCreateRequest(slug, "幂等标题", "换了正文", null, null, null, null)));
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT, conflict.code());

        // 同一幂等键重复提交必须短路，不能把第二次当成"状态已变"的 409
        String documentId = readyDocument(s, s.authorId());
        wiki.addSource(s.author(), first.id(), new Dto.WikiSourceRequest("DOCUMENT", documentId, 1, 0, null));
        String submitKey = key();
        wiki.submit(s.author(), first.id(), "1", submitKey);
        assertEquals("IN_REVIEW", wiki.submit(s.author(), first.id(), "1", submitKey).status());
        assertEquals(1, auditCount(s.tenantId(), "wiki.submit", "ALLOW"));

        // 幂等键必须是 UUID：给 "abc" 这类值直接 400，不留"键碰撞"的模糊地带
        assertEquals(ErrorCode.VALIDATION_FAILED, assertThrows(ApiException.class,
                () -> wiki.create(s.author(), s.kbId(), "abc", new Dto.WikiPageCreateRequest(
                        "other-" + id().substring(0, 8), "标题", "正文", null, null, null, null))).code());
    }

    /** slug 在同库内唯一（V17 `uk_wiki_page_kb_slug`），冲突转 400 且不回传对方标题。 */
    @Test
    void duplicateSlugWithinTheSameKbIsRejected() {
        Space s = space();
        String slug = "dup-" + id().substring(0, 8);
        wiki.create(s.author(), s.kbId(), key(), new Dto.WikiPageCreateRequest(slug, "原标题", "正文", null,
                null, null, null));

        ApiException error = assertThrows(ApiException.class, () -> wiki.create(s.author(), s.kbId(), key(),
                new Dto.WikiPageCreateRequest(slug, "撞名的标题", "别的正文", null, null, null, null)));
        assertEquals(ErrorCode.VALIDATION_FAILED, error.code());
        assertFalse(error.getMessage().contains("撞名的标题"));
        assertEquals(1, count("SELECT COUNT(*) FROM wiki_page WHERE kb_id = ?", s.kbId()));

        // 换一个库就允许同 slug：唯一键作用域是 (kb_id, slug)
        String otherKb = id();
        scopes.insertKnowledgeBase(otherKb, s.tenantId(), s.workspaceId(), "kb2-" + otherKb.substring(0, 8),
                "第二个库", "weknora", Classification.INTERNAL.name(), Instant.now());
        assertEquals(slug, wiki.create(s.author(), otherKb, key(), new Dto.WikiPageCreateRequest(slug,
                "另一个库的同名页", "正文", null, null, null, null)).slug());
    }
}
