package com.acme.kb.p1.wiki;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约 §2 的边表内容本身由 `p1/scripts/24-check-contracts-p1.py` 与冻结件逐条比对；
 * 本类证明的是脚本查不出来的那半边——§3 禁止迁移、终态无出边、三轴正交与新鲜度不建边。
 */
class WikiStateMachineTest {

    private static final Pattern AUDIT_ACTION = Pattern.compile("^[a-z][a-z0-9.]{2,63}$");

    @Test
    void edgeTableHasTheContractedShape() {
        assertEquals(21, WikiStateMachine.EDGES.size());
        assertEquals(8, edges(WikiStateMachine.PAGE).size());
        assertEquals(4, edges(WikiStateMachine.REVISION).size());
        assertEquals(9, edges(WikiStateMachine.SYNC).size());
    }

    @Test
    void edgeIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (WikiStateMachine.Edge e : WikiStateMachine.EDGES) {
            assertTrue(ids.add(e.id()), "边 id 重复：" + e.id());
        }
    }

    /** 状态名必须同时是枚举常量，否则库里写入的状态与边表对不上（§2 比对规则的后半句）。 */
    @Test
    void everyEdgeStateIsAnEnumConstant() {
        for (WikiStateMachine.Edge e : WikiStateMachine.EDGES) {
            state(e.axis(), e.from());
            state(e.axis(), e.to());
        }
    }

    /** 反过来也要成立：枚举里不能凭空多出一个没人能到达、也没人离得开的状态。 */
    @Test
    void everyEnumConstantAppearsInAnEdge() {
        assertConstantInEdge(WikiStateMachine.PAGE, WikiPageStatus.values());
        assertConstantInEdge(WikiStateMachine.REVISION, WikiRevisionStatus.values());
        assertConstantInEdge(WikiStateMachine.SYNC, WikiSyncStatus.values());
    }

    @Test
    void auditActionNamesFollowTheSchema() {
        for (WikiStateMachine.Edge e : WikiStateMachine.EDGES) {
            String action = e.auditAction();
            if (action.isEmpty()) {
                assertTrue(WikiStateMachine.SYNC.equals(e.axis()), "只有 SYNC 的认领类边允许无审计动作：" + e.id());
                continue;
            }
            assertTrue(AUDIT_ACTION.matcher(action).matches(), "审计动作名不合法：" + action);
        }
        assertEquals("wiki.submit", WikiStateMachine.auditAction(WikiStateMachine.PAGE, "DRAFT", "IN_REVIEW"));
        assertEquals("wiki.approve", WikiStateMachine.auditAction(WikiStateMachine.REVISION, "APPROVED", "SUPERSEDED"));
        assertEquals("", WikiStateMachine.auditAction(WikiStateMachine.SYNC, "PENDING", "CLAIMED"));
    }

    /** §3：这些迁移一律无边（服务层据此把 0 行更新转成 409 + DENY 审计）。 */
    @Test
    void contractForbiddenTransitionsHaveNoEdge() {
        assertFalse(WikiStateMachine.canTransition(WikiStateMachine.PAGE,
                WikiPageStatus.PUBLISHED, WikiPageStatus.ARCHIVED));
        assertFalse(WikiStateMachine.canTransition(WikiStateMachine.PAGE,
                WikiPageStatus.RETRACTING, WikiPageStatus.ARCHIVED));
        assertFalse(WikiStateMachine.canTransition(WikiStateMachine.PAGE,
                WikiPageStatus.IN_REVIEW, WikiPageStatus.ARCHIVED));
        assertFalse(WikiStateMachine.canTransition(WikiStateMachine.PAGE,
                WikiPageStatus.RETRACTED, WikiPageStatus.IN_REVIEW));
        assertFalse(WikiStateMachine.canTransition(WikiStateMachine.PAGE,
                WikiPageStatus.ARCHIVED, WikiPageStatus.IN_REVIEW));
        assertFalse(WikiStateMachine.canTransition("PAGE", "DRAFT", "PUBLISHED"));
        assertFalse(WikiStateMachine.canTransition(WikiStateMachine.REVISION,
                WikiRevisionStatus.APPROVED, WikiRevisionStatus.DRAFT));
    }

    /** §3 终态清单：重放必须走新行，终态上不允许任何出边。 */
    @Test
    void terminalStatesHaveNoOutgoingEdges() {
        for (String state : List.of("REJECTED", "SUPERSEDED")) {
            assertEquals(Set.of(), WikiStateMachine.targets(WikiStateMachine.REVISION, state), state + " 不该有出边");
        }
        for (String state : List.of("SUCCEEDED", "FAILED", "DEAD_LETTERED", "COMPENSATION_REQUIRED")) {
            assertEquals(Set.of(), WikiStateMachine.targets(WikiStateMachine.SYNC, state), state + " 不该有出边");
        }
        assertEquals(Set.of(), WikiStateMachine.targets(WikiStateMachine.PAGE, "ARCHIVED"));
    }

    /** 枚举自带的 terminal() 是治理视图的判据，必须与边表算出来的结果一致。 */
    @Test
    void enumTerminalityAgreesWithEdgeTable() {
        for (WikiPageStatus s : WikiPageStatus.values()) {
            assertEquals(s.terminal(), WikiStateMachine.terminal(WikiStateMachine.PAGE, s.name()), s.name());
        }
        for (WikiRevisionStatus s : WikiRevisionStatus.values()) {
            assertEquals(s.terminal(), WikiStateMachine.terminal(WikiStateMachine.REVISION, s.name()), s.name());
        }
        for (WikiSyncStatus s : WikiSyncStatus.values()) {
            assertEquals(s.terminal(), WikiStateMachine.terminal(WikiStateMachine.SYNC, s.name()), s.name());
        }
    }

    @Test
    void unknownAxisAndStateAreNeverTerminal() {
        assertFalse(WikiStateMachine.terminal(WikiStateMachine.PAGE, "NOT_A_STATE"));
        assertFalse(WikiStateMachine.terminal("FRESHNESS", "FRESH"));
        assertFalse(WikiStateMachine.canTransition("FRESHNESS", "FRESH", "STALE"));
    }

    /** D-02 正交性的可执行表达：新鲜度取值一次都不出现在边表里。 */
    @Test
    void freshnessIsADerivedBitNotASStateMachineAxis() {
        for (WikiFreshness f : WikiFreshness.values()) {
            assertTrue(WikiStateMachine.EDGES.stream().noneMatch(
                    e -> e.from().equals(f.name()) || e.to().equals(f.name())), f + " 不该出现在状态机里");
        }
    }

    /** §2 注：撤稿一旦发起就没有边把页面送回 PUBLISHED。 */
    @Test
    void retractedContentCannotGoStraightBackToPublished() {
        assertFalse(WikiStateMachine.find(WikiStateMachine.PAGE, "RETRACTED", "PUBLISHED").isPresent());
        assertFalse(WikiStateMachine.find(WikiStateMachine.PAGE, "RETRACTING", "PUBLISHED").isPresent());
        assertEquals(Set.of("RETRACTED"), WikiStateMachine.targets(WikiStateMachine.PAGE, "RETRACTING"));
    }

    /** E4 是自环：同一页面发布第 N+1 修订时页面态不变，只有指针与修订态动。 */
    @Test
    void republishIsASelfLoopOnPublished() {
        WikiStateMachine.Edge e4 = WikiStateMachine.requireEdge(WikiStateMachine.PAGE, "PUBLISHED", "PUBLISHED");
        assertEquals("E4", e4.id());
        assertEquals("approve", e4.trigger());
        assertEquals("wiki.approve", e4.auditAction());
    }

    @Test
    void requireEdgeRejectsMissingEdge() {
        ApiException e = assertThrows(ApiException.class,
                () -> WikiStateMachine.requireEdge(WikiStateMachine.PAGE, "DRAFT", "PUBLISHED"));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code());
        assertEquals(409, e.code().status().value());
    }

    /** 条件更新前后拿到的是库里的状态名，两个重载必须同判。 */
    @Test
    void enumAndStringOverloadsAgree() {
        for (WikiStateMachine.Edge e : WikiStateMachine.EDGES) {
            assertTrue(WikiStateMachine.canTransition(e.axis(), state(e.axis(), e.from()), state(e.axis(), e.to())),
                    e.id() + " 的枚举版必须为真");
            assertTrue(WikiStateMachine.canTransition(e.axis(), e.from(), e.to()), e.id() + " 的字符串版必须为真");
        }
        assertFalse(WikiStateMachine.canTransition(WikiStateMachine.PAGE,
                WikiPageStatus.DRAFT, WikiRevisionStatus.APPROVED));
    }

    @Test
    void syncClaimEdgesFollowTheLeaseContract() {
        for (String from : List.of("PENDING", "RETRY_SCHEDULED")) {
            assertEquals("claim", WikiStateMachine.requireEdge(WikiStateMachine.SYNC, from, "CLAIMED").trigger());
            assertTrue(WikiSyncStatus.parse(from).claimable(), from + " 必须可被认领");
        }
        for (WikiSyncStatus s : WikiSyncStatus.values()) {
            assertEquals(s.claimable(),
                    WikiStateMachine.targets(WikiStateMachine.SYNC, s.name()).contains("CLAIMED"), s.name());
        }
    }

    private static List<WikiStateMachine.Edge> edges(String axis) {
        return WikiStateMachine.EDGES.stream().filter(e -> e.axis().equals(axis)).toList();
    }

    private static void assertConstantInEdge(String axis, Enum<?>[] states) {
        for (Enum<?> s : states) {
            String name = s.name();
            assertTrue(edges(axis).stream().anyMatch(e -> e.from().equals(name) || e.to().equals(name)),
                    axis + " 轴的 " + name + " 没有出现在任何边上");
        }
    }

    private static Enum<?> state(String axis, String name) {
        return switch (axis) {
            case WikiStateMachine.PAGE -> WikiPageStatus.valueOf(name);
            case WikiStateMachine.REVISION -> WikiRevisionStatus.valueOf(name);
            case WikiStateMachine.SYNC -> WikiSyncStatus.valueOf(name);
            default -> throw new IllegalArgumentException("未知轴：" + axis);
        };
    }
}
