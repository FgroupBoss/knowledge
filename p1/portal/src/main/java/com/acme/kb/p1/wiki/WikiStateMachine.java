package com.acme.kb.p1.wiki;

import com.acme.kb.p1.common.ApiException;
import com.acme.kb.p1.common.ErrorCode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Wiki 三轴状态机的边表，与冻结件 `p1/contracts/state-machine-wiki.md` §2 逐条机器比对
 * （`python p1/scripts/24-check-contracts-p1.py`，缺边或多边即 ERROR）。
 *
 * 每条形如 `edge("E1", "PAGE", "DRAFT", "IN_REVIEW", "submit", "wiki.submit")`：
 * 前四参是比对键，trigger 与 auditAction 为人读口径与审计动作名。
 *
 * 状态迁移在库里一律走 `UPDATE ... WHERE status = 前置态`；本类只回答"这条边存不存在"，
 * 影响 0 行时由服务层调用 {@link #require} 之外的路径重新读状态并记 DENY 审计。
 */
public final class WikiStateMachine {

    /** 一条迁移边。axis 取 PAGE / REVISION / SYNC，from/to 必须是同轴枚举常量名。 */
    public record Edge(String id, String axis, String from, String to, String trigger, String auditAction) {
    }

    private static Edge edge(String id, String axis, String from, String to, String trigger,
                            String auditAction) {
        return new Edge(id, axis, from, to, trigger, auditAction);
    }

    public static final List<Edge> EDGES = List.of(
            // PAGE 轴（审核态）
            edge("E1", "PAGE", "DRAFT", "IN_REVIEW", "submit", "wiki.submit"),
            edge("E2", "PAGE", "IN_REVIEW", "DRAFT", "reject", "wiki.reject"),
            edge("E3", "PAGE", "IN_REVIEW", "PUBLISHED", "approve", "wiki.approve"),
            edge("E4", "PAGE", "PUBLISHED", "PUBLISHED", "approve", "wiki.approve"),
            edge("E5", "PAGE", "PUBLISHED", "RETRACTING", "retract", "wiki.retract"),
            edge("E6", "PAGE", "RETRACTING", "RETRACTED", "retract-confirmed", "wiki.retract"),
            edge("E7", "PAGE", "DRAFT", "ARCHIVED", "archive", "wiki.archive"),
            edge("E8", "PAGE", "RETRACTED", "ARCHIVED", "archive", "wiki.archive"),
            // REVISION 轴（修订态）
            edge("R1", "REVISION", "DRAFT", "PENDING", "submit", "wiki.submit"),
            edge("R2", "REVISION", "PENDING", "APPROVED", "approve", "wiki.approve"),
            edge("R3", "REVISION", "PENDING", "REJECTED", "reject", "wiki.reject"),
            edge("R4", "REVISION", "APPROVED", "SUPERSEDED", "approve", "wiki.approve"),
            // SYNC 轴（同步态，W1 只冻结边表，消费者 W2 接线）
            edge("S1", "SYNC", "PENDING", "CLAIMED", "claim", ""),
            edge("S2", "SYNC", "CLAIMED", "RUNNING", "start", ""),
            edge("S3", "SYNC", "CLAIMED", "RETRY_SCHEDULED", "lease-expired", ""),
            edge("S4", "SYNC", "RUNNING", "SUCCEEDED", "succeeded", "wiki.publish.sync"),
            edge("S5", "SYNC", "RUNNING", "RETRY_SCHEDULED", "transient-failure", "wiki.publish.sync"),
            edge("S6", "SYNC", "RUNNING", "FAILED", "permanent-failure", "wiki.publish.sync"),
            edge("S7", "SYNC", "RUNNING", "DEAD_LETTERED", "attempts-exhausted", "wiki.publish.sync"),
            edge("S8", "SYNC", "RUNNING", "COMPENSATION_REQUIRED", "retract-failure", "wiki.retract"),
            edge("S9", "SYNC", "RETRY_SCHEDULED", "CLAIMED", "claim", ""));

    public static final String PAGE = "PAGE";
    public static final String REVISION = "REVISION";
    public static final String SYNC = "SYNC";

    private static final Map<String, Set<Edge>> BY_AXIS = indexByAxis();

    private WikiStateMachine() {
    }

    private static Map<String, Set<Edge>> indexByAxis() {
        Map<String, Set<Edge>> out = new LinkedHashMap<>();
        for (Edge e : EDGES) {
            out.computeIfAbsent(e.axis(), k -> new LinkedHashSet<>()).add(e);
        }
        return out;
    }

    public static Optional<Edge> find(String axis, String from, String to) {
        return BY_AXIS.getOrDefault(axis, Set.of()).stream()
                .filter(e -> e.from().equals(from) && e.to().equals(to))
                .findFirst();
    }

    public static boolean canTransition(String axis, Enum<?> from, Enum<?> to) {
        return canTransition(axis, from.name(), to.name());
    }

    /** 字符串版：库里存的是状态名，写路径的条件更新前后都用它判定边的存在。 */
    public static boolean canTransition(String axis, String from, String to) {
        return find(axis, from, to).isPresent();
    }

    /** 这条边不存在即非法迁移：调用方须重新读状态后转成 INVALID_STATE_TRANSITION(409) 并写 DENY 审计。 */
    public static Edge requireEdge(String axis, String from, String to) {
        return find(axis, from, to).orElseThrow(
                () -> new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "当前状态不允许该操作"));
    }

    /** 出边集合为空即终态；终态重放必须走新行（§3）。 */
    public static Set<String> targets(String axis, String from) {
        Set<String> out = new LinkedHashSet<>();
        for (Edge e : BY_AXIS.getOrDefault(axis, Set.of())) {
            if (e.from().equals(from)) {
                out.add(e.to());
            }
        }
        return out;
    }

    public static boolean terminal(String axis, String state) {
        return known(axis, state) && targets(axis, state).isEmpty();
    }

    private static boolean known(String axis, String state) {
        return BY_AXIS.getOrDefault(axis, Set.of()).stream()
                .anyMatch(e -> e.from().equals(state) || e.to().equals(state));
    }

    /** 审计动作名（点小写，schema 的 ^[a-z][a-z0-9.]{2,63}$ 强制）。 */
    public static String auditAction(String axis, String from, String to) {
        return find(axis, from, to).map(Edge::auditAction).orElse("");
    }
}
