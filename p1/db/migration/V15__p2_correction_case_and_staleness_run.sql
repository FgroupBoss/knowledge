-- P2-W1 纠错工单与新鲜度扫描批次（表先落，端点与状态迁移在 W3 接线）
CREATE TABLE correction_case (
  id                      CHAR(36)      NOT NULL,
  tenant_id               CHAR(36)      NOT NULL,
  workspace_id            CHAR(36)      NOT NULL,
  kb_id                   CHAR(36)      NOT NULL,
  feedback_id             CHAR(36)      NOT NULL,
  -- 目标二选一：WIKI_PAGE / DOCUMENT
  target_type             VARCHAR(16)   NOT NULL,
  target_id               CHAR(36)      NULL,
  -- D-11：NEW → TRIAGED → ASSIGNED → RESOLVED | REJECTED | WONTFIX
  status                  VARCHAR(16)   NOT NULL DEFAULT 'NEW',
  assignee_user_id        CHAR(36)      NULL,
  due_at                  DATETIME(3)   NULL,
  -- RESOLVED 必须关联修订或文档，否则 VALIDATION_FAILED
  resolution_revision_id  CHAR(36)      NULL,
  resolution_document_id  CHAR(36)      NULL,
  -- 处置说明原文只留摘要哈希（红线 1）
  resolution_note_digest  CHAR(64)      NULL,
  created_at              DATETIME(3)   NOT NULL,
  closed_at               DATETIME(3)   NULL,
  PRIMARY KEY (id),
  -- 一条反馈只开一张工单：分诊重复执行不产生第二单
  UNIQUE KEY uk_case_feedback (feedback_id),
  KEY idx_case_queue (tenant_id, status, due_at),
  KEY idx_case_assignee (assignee_user_id, status),
  KEY idx_case_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 新鲜度扫描批次，形态沿用 reconciliation_run：证据只写计数与路径，不写正文
CREATE TABLE wiki_staleness_run (
  id            CHAR(36)     NOT NULL,
  started_at    DATETIME(3)  NOT NULL,
  finished_at   DATETIME(3)  NULL,
  scanned       INT          NOT NULL DEFAULT 0,
  marked        INT          NOT NULL DEFAULT 0,
  counts_json   JSON         NULL,
  evidence_path VARCHAR(512) NULL,
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_stale_run_time (started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
