package com.acme.kb.p1.wiki;

import java.util.Set;

/** 修订态（`wiki_revision.status`），对应契约 §2 的 REVISION 轴（R1…R4）。 */
public enum WikiRevisionStatus {
    DRAFT,
    PENDING,
    APPROVED,
    REJECTED,
    SUPERSEDED;

    /** §3：REJECTED 与 SUPERSEDED 无任何出边，改动等于开新修订。 */
    private static final Set<WikiRevisionStatus> TERMINAL = Set.of(REJECTED, SUPERSEDED);

    /** 只有 DRAFT 可原地改正文；APPROVED 之后的改动必须落新修订（§3 禁止 APPROVED → DRAFT）。 */
    public boolean editable() {
        return this == DRAFT;
    }

    /** 审核只作用于 PENDING 修订，其余状态一律 409。 */
    public boolean reviewable() {
        return this == PENDING;
    }

    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public static WikiRevisionStatus parse(String raw) {
        return valueOf(raw.strip().toUpperCase());
    }
}
