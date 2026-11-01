package com.acme.kb.p1.wiki;

import java.util.Set;

/**
 * 审核态（`wiki_page.status`），与 `p1/contracts/state-machine-wiki.md` §2 的 PAGE 轴逐字一致。
 * 与修订态、同步态正交（D-02）：本枚举不含新鲜度取值，新鲜度是派生位、不建边。
 */
public enum WikiPageStatus {
    DRAFT,
    IN_REVIEW,
    PUBLISHED,
    RETRACTING,
    RETRACTED,
    ARCHIVED;

    private static final Set<WikiPageStatus> BODY_DENIED = Set.of(RETRACTING, RETRACTED, ARCHIVED);

    /** 检索与引用预览的拒绝优先于派生索引：已进入下线流程的页面一律不给正文。 */
    public boolean bodyDenied() {
        return BODY_DENIED.contains(this);
    }

    /** 终态：没有任何出边，重新上线必须开新修订走 E1/E3。 */
    public boolean terminal() {
        return this == ARCHIVED;
    }

    public static WikiPageStatus parse(String raw) {
        return valueOf(raw.strip().toUpperCase());
    }
}
