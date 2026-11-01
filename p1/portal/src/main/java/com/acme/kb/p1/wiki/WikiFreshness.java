package com.acme.kb.p1.wiki;

/**
 * 新鲜度（`wiki_page.freshness`）。契约 §1 明确它是**派生位、不建状态边**：
 * 写入方是 W3 的定时扫描（staleness run），因此 P2-W1 的写路径只在建页时置 `FRESH`，
 * 之后不由任何审核动作改动——把 STALE 塞进审核边表会让同一条边按有效期走两套校验。
 */
public enum WikiFreshness {
    FRESH,
    STALE,
    EXPIRED;

    public static WikiFreshness parse(String raw) {
        return valueOf(raw.strip().toUpperCase());
    }
}
