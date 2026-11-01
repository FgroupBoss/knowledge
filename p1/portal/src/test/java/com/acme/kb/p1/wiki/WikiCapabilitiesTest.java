package com.acme.kb.p1.wiki;

import com.acme.kb.p1.auth.Actor;
import com.acme.kb.p1.auth.WorkspaceRole;
import com.acme.kb.p1.authz.AclDecision;
import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.Classification;
import com.acme.kb.p1.common.ErrorCode;
import com.acme.kb.p1.repo.WikiQueryMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-05 的可执行证明：审核能力来自 `wiki_review_assignment` 的行，不来自新角色枚举，
 * 因此 P1 的授权矩阵证据（`05-verify-authz.py`）不会因为 P2 而失效。
 * 这些列表只用于前端置灰，服务端每个动作仍独立判定（见 WikiService）。
 */
class WikiCapabilitiesTest {

    private static final WikiCapabilities NO_MAPPER = new WikiCapabilities(null);

    @Test
    void contributorAuthorSeesOwnDraftButCannotReviewIt() {
        WikiCapabilities.Grants g = NO_MAPPER.compute(author(), kb(WorkspaceRole.CONTRIBUTOR),
                page("u-owner"), revision("u-author"), false);
        assertTrue(g.read());
        assertTrue(g.write());
        assertTrue(g.submit());
        assertTrue(g.readDraft(), "作者当然看得到自己的草稿");
        assertFalse(g.review());
        assertFalse(g.govern());
        assertFalse(g.retract());
        assertTrue(g.names().contains(WikiCapabilities.READ_DRAFT));
        assertFalse(g.names().contains(WikiCapabilities.REVIEW));
    }

    /** 被指派的 VIEWER 也要能读到待审正文，否则审核队列里会出现读不了的单子（契约 getWikiPage 描述）。 */
    @Test
    void assignedViewerReviewerReadsAndReviewsDraft() {
        WikiCapabilities.Grants g = NO_MAPPER.compute(reviewer(), kb(WorkspaceRole.VIEWER),
                page("u-owner"), revision("u-author"), true);
        assertTrue(g.read());
        assertFalse(g.write());
        assertFalse(g.submit());
        assertTrue(g.review());
        assertTrue(g.readDraft());
        assertTrue(g.retract(), "审核人可撤下自己审核通过的页面");
        assertFalse(g.govern());
    }

    @Test
    void privilegedRolesReviewGovernAndRetract() {
        for (WorkspaceRole role : List.of(WorkspaceRole.OWNER, WorkspaceRole.ADMIN)) {
            WikiCapabilities.Grants g = NO_MAPPER.compute(stranger(), kb(role),
                    page("u-owner"), revision("u-author"), false);
            assertTrue(g.review(), role + " 天然具备审核能力");
            assertTrue(g.govern(), role + " 天然具备治理能力");
            assertTrue(g.retract());
            assertTrue(g.readDraft());
        }
    }

    /** 负责人可以治理与撤稿，但不等于能审核——自批另有 E3 前置校验（§5 用例 2）。 */
    @Test
    void pageOwnerGovernesWithoutGainingReview() {
        WikiCapabilities.Grants g = NO_MAPPER.compute(owner(), kb(WorkspaceRole.CONTRIBUTOR),
                page("u-owner"), revision("u-author"), false);
        assertTrue(g.govern());
        assertTrue(g.retract());
        assertTrue(g.readDraft());
        assertFalse(g.review(), "负责人身份不产生审核能力");
    }

    @Test
    void outsiderWithReadCannotTouchAnything() {
        WikiCapabilities.Grants g = NO_MAPPER.compute(stranger(), kb(WorkspaceRole.VIEWER),
                page("u-owner"), revision("u-author"), false);
        assertTrue(g.read());
        assertFalse(g.write());
        assertFalse(g.readDraft());
        assertFalse(g.review());
        assertFalse(g.govern());
        assertEquals(List.of(WikiCapabilities.READ), g.names());
    }

    @Test
    void capabilityNamesAreStableAndOrdered() {
        WikiCapabilities.Grants all = new WikiCapabilities.Grants(true, true, true, true, true, true, true);
        assertEquals(List.of("wiki:read", "wiki:read-draft", "wiki:write", "wiki:submit",
                "wiki:review", "wiki:retract", "wiki:govern"), all.names());
    }

    /** 缺失审核能力走专用码：客户端动作是"去走指派流程"，不是"停止"。 */
    @Test
    void requireReviewUsesTheDedicatedCode() {
        WikiCapabilities.Grants g = NO_MAPPER.compute(author(), kb(WorkspaceRole.CONTRIBUTOR),
                page("u-owner"), revision("u-author"), false);
        assertEquals(ErrorCode.WIKI_REVIEWER_NOT_ASSIGNED,
                assertThrows(ApiException.class, () -> NO_MAPPER.requireReview(g)).code());
        NO_MAPPER.requireReview(new WikiCapabilities.Grants(true, true, true, true, true, true, true));
    }

    @Test
    void otherGuardsFallBackToAccessDenied() {
        WikiCapabilities.Grants none = new WikiCapabilities.Grants(false, false, false, false, false, false, false);
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(ApiException.class,
                () -> NO_MAPPER.requireWrite(none)).code());
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(ApiException.class,
                () -> NO_MAPPER.requireSubmit(none)).code());
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(ApiException.class,
                () -> NO_MAPPER.requireRetract(none)).code());
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(ApiException.class,
                () -> NO_MAPPER.requireGovern(none)).code());
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(ApiException.class,
                () -> NO_MAPPER.requireRead(none)).code());
    }

    /** 四参重载必须去查指派表——列表页靠五参重载省查询，单页靠它拿到真实能力。 */
    @Test
    void fourArgOverloadConsultsTheAssignmentTable() {
        WikiQueryMapper oneRow = mapperWithAssignments(1);
        WikiQueryMapper noRows = mapperWithAssignments(0);
        assertTrue(new WikiCapabilities(oneRow).compute(reviewer(), kb(WorkspaceRole.VIEWER),
                page("u-owner"), revision("u-author")).review());
        assertFalse(new WikiCapabilities(noRows).compute(reviewer(), kb(WorkspaceRole.VIEWER),
                page("u-owner"), revision("u-author")).review());
        assertFalse(new WikiCapabilities(noRows).assigned(reviewer(), page("u-owner")));
        assertTrue(new WikiCapabilities(oneRow).assigned(reviewer(), page("u-owner")));
    }

    private static WikiQueryMapper mapperWithAssignments(int rows) {
        return (WikiQueryMapper) Proxy.newProxyInstance(WikiQueryMapper.class.getClassLoader(),
                new Class<?>[]{WikiQueryMapper.class},
                (proxy, method, args) -> "reviewAssigned".equals(method.getName()) ? rows : null);
    }

    private static AclDecision kb(WorkspaceRole role) {
        return new AclDecision("kb-1", "any", "tenant-1", "ws-1", role.name(),
                role.getPermissions(), Classification.INTERNAL.name(), 1L, true, 0L);
    }

    private static WikiQueryMapper.PageRow page(String ownerUserId) {
        return new WikiQueryMapper.PageRow("page-1", "tenant-1", "ws-1", "kb-1", "run-book", "标题",
                "DRAFT", "FRESH", ownerUserId, 1, null, null, null, null, 1L, null, null);
    }

    private static WikiQueryMapper.RevisionRow revision(String authorUserId) {
        return new WikiQueryMapper.RevisionRow("rev-1", "page-1", 1, authorUserId, "DRAFT", "正文",
                "digest", "摘要", null, null, null, null, null);
    }

    private static Actor author() {
        return actor("u-author");
    }

    private static Actor reviewer() {
        return actor("u-reviewer");
    }

    private static Actor owner() {
        return actor("u-owner");
    }

    private static Actor stranger() {
        return actor("u-stranger");
    }

    private static Actor actor(String userId) {
        return new Actor(userId, "tenant-1", "t1", userId, userId, Classification.INTERNAL);
    }
}
