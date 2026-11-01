package com.acme.kb.p1.wiki;

import java.util.Set;

/**
 * 同步态（`wiki_sync_task.status`），对应契约 §2 的 SYNC 轴（S1…S9）。
 * 边表在 P2-W1 冻结，消费者（`WikiSyncConsumer`/`WikiRetractConsumer`）需准入 F-1 真引擎，属 W2，
 * 因此本轮任务行只会停在 PENDING，没有任何代码路径推进这些状态。
 */
public enum WikiSyncStatus {
    PENDING,
    CLAIMED,
    RUNNING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    FAILED,
    COMPENSATION_REQUIRED,
    DEAD_LETTERED;

    private static final Set<WikiSyncStatus> TERMINAL =
            Set.of(SUCCEEDED, FAILED, DEAD_LETTERED, COMPENSATION_REQUIRED);

    /** 可被重新认领：未过期租约的 CLAIMED/RUNNING 不在此列，由租约条件挡住。 */
    public boolean claimable() {
        return this == PENDING || this == RETRY_SCHEDULED;
    }

    public boolean leaseExpiredReclaimable() {
        return this == CLAIMED || this == RUNNING;
    }

    /** 终态只能由新任务行重放，旧行留作取证（§3）。 */
    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    public static WikiSyncStatus parse(String raw) {
        return valueOf(raw.strip().toUpperCase());
    }
}
