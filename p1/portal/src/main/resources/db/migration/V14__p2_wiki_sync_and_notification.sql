-- P2-W1 引擎同步任务、可重放提醒与站内通知（D-09：一律表驱动，不用进程内定时器）
CREATE TABLE wiki_sync_task (
  id            CHAR(36)     NOT NULL,
  tenant_id     CHAR(36)     NOT NULL,
  page_id       CHAR(36)     NOT NULL,
  revision_no   INT          NOT NULL,
  -- PUBLISH 走 kb.wiki.sync.v1，RETRACT 走 kb.wiki.retract.v1（D-04：分主题分消费者组）
  operation     VARCHAR(16)  NOT NULL,
  -- 同步态：PENDING / CLAIMED / RUNNING / RETRY_SCHEDULED / SUCCEEDED / FAILED
  --          / COMPENSATION_REQUIRED / DEAD_LETTERED
  status        VARCHAR(32)  NOT NULL,
  attempts      INT          NOT NULL DEFAULT 0,
  max_attempts  INT          NOT NULL DEFAULT 6,
  -- 退避认领只靠 next_run_at <= now，不靠 sleep
  next_run_at   DATETIME(3)  NULL,
  lease_owner   VARCHAR(64)  NULL,
  lease_until   DATETIME(3)  NULL,
  engine_doc_id VARCHAR(128) NULL,
  error_code    VARCHAR(64)  NULL,
  error_reason  VARCHAR(512) NULL,
  correlation_id CHAR(36)    NULL,
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  -- 一页一修订一操作只有一条任务：重复 approve/retract 不产生第二份索引
  UNIQUE KEY uk_wiki_task (page_id, revision_no, operation),
  KEY idx_wiki_task_claim (operation, status, next_run_at),
  KEY idx_wiki_task_lease (status, lease_until),
  KEY idx_wiki_task_tenant (tenant_id, page_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 提醒按 (page_id, rule, date_bucket) 幂等：同一天重复扫描只恰一条（§5.2 用例 12）
CREATE TABLE wiki_reminder (
  id             CHAR(36)     NOT NULL,
  tenant_id      CHAR(36)     NOT NULL,
  page_id        CHAR(36)     NOT NULL,
  -- 规则：STALE / EXPIRING / REVIEW_SLA（S9 边）
  rule           VARCHAR(32)  NOT NULL,
  target_user_id CHAR(36)     NOT NULL,
  -- ISO 日期桶（YYYY-MM-DD），与 date_bucket 唯一键一起构成"同日只发一次"
  date_bucket    CHAR(10)     NOT NULL,
  status         VARCHAR(16)  NOT NULL,
  attempts       INT          NOT NULL DEFAULT 0,
  next_run_at    DATETIME(3)  NULL,
  notified_at    DATETIME(3)  NULL,
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_wiki_remind_claim (status, next_run_at),
  KEY idx_wiki_remind_page (tenant_id, page_id, rule)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- D-08：只做站内通知，不接外部邮件/IM
CREATE TABLE notification (
  id           CHAR(36)     NOT NULL,
  tenant_id    CHAR(36)     NOT NULL,
  user_id      CHAR(36)     NOT NULL,
  type         VARCHAR(32)  NOT NULL,
  -- 标题只留摘要哈希；正文话术不含 Wiki 正文与文档标题原文（红线 1）
  title_digest CHAR(64)     NOT NULL,
  body         VARCHAR(512) NULL,
  link_type    VARCHAR(32)  NULL,
  link_id      CHAR(36)     NULL,
  read_at      DATETIME(3)  NULL,
  created_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_notify_inbox (tenant_id, user_id, read_at, created_at),
  KEY idx_notify_link (link_type, link_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
