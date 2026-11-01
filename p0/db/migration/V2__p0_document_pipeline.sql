-- P0-2 文档生命周期、任务、事务 Outbox 与审计
CREATE TABLE document (
  id             CHAR(36)     NOT NULL,
  tenant_id      CHAR(36)     NOT NULL,
  workspace_id   CHAR(36)     NOT NULL,
  kb_id          CHAR(36)     NOT NULL,
  display_name   VARCHAR(255) NOT NULL,
  version        INT          NOT NULL,
  object_key     VARCHAR(512) NOT NULL,
  sha256         CHAR(64)     NOT NULL,
  size_bytes     BIGINT       NOT NULL,
  mime_type      VARCHAR(128) NOT NULL,
  extension      VARCHAR(16)  NOT NULL,
  classification VARCHAR(16)  NOT NULL,
  status         VARCHAR(32)  NOT NULL,
  engine_doc_id  VARCHAR(128) NULL,
  acl_enforced   TINYINT(1)   NOT NULL DEFAULT 0,
  error_code     VARCHAR(64)  NULL,
  error_summary  VARCHAR(512) NULL,
  created_by     CHAR(36)     NULL,
  created_at     DATETIME(3)  NOT NULL,
  updated_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_kb_hash_version (kb_id, sha256, version),
  KEY idx_doc_kb_status (kb_id, status),
  KEY idx_doc_tenant_status (tenant_id, status),
  KEY idx_doc_engine (engine_doc_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 任务生命周期与重放：operation_id 唯一，一个操作只有一个任务
CREATE TABLE ingest_task (
  id             CHAR(36)     NOT NULL,
  tenant_id      CHAR(36)     NOT NULL,
  document_id    CHAR(36)     NOT NULL,
  document_ver   INT          NOT NULL,
  operation_id   CHAR(36)     NOT NULL,
  task_type      VARCHAR(16)  NOT NULL,
  status         VARCHAR(32)  NOT NULL,
  attempts       INT          NOT NULL DEFAULT 0,
  max_attempts   INT          NOT NULL DEFAULT 5,
  next_run_at    DATETIME(3)  NULL,
  lease_owner    VARCHAR(64)  NULL,
  lease_until    DATETIME(3)  NULL,
  error_code     VARCHAR(64)  NULL,
  error_summary  VARCHAR(512) NULL,
  correlation_id CHAR(36)     NULL,
  started_at     DATETIME(3)  NULL,
  finished_at    DATETIME(3)  NULL,
  created_at     DATETIME(3)  NOT NULL,
  updated_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_task_operation (operation_id),
  KEY idx_task_claim (status, next_run_at),
  KEY idx_task_doc (document_id, document_ver)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 事务 Outbox：业务事务写入，发布器投递后标记，DB 与 Kafka 之间不引入分布式事务
CREATE TABLE outbox_event (
  id              CHAR(36)     NOT NULL,
  tenant_id       CHAR(36)     NOT NULL,
  event_id        CHAR(36)     NOT NULL,
  aggregate_id    CHAR(36)     NOT NULL,
  aggregate_type  VARCHAR(32)  NOT NULL,
  event_type      VARCHAR(64)  NOT NULL,
  topic           VARCHAR(128) NOT NULL,
  partition_key   VARCHAR(64)  NOT NULL,
  schema_version  INT          NOT NULL DEFAULT 1,
  payload         JSON         NOT NULL,
  headers         JSON         NULL,
  created_at      DATETIME(3)  NOT NULL,
  published_at    DATETIME(3)  NULL,
  attempts        INT          NOT NULL DEFAULT 0,
  last_error      VARCHAR(512) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_event_id (event_id),
  KEY idx_outbox_unpublished (published_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 消费端幂等账本：eventId 只处理一次（重复投递不产生第二个有效索引）
CREATE TABLE event_consumption (
  event_id       CHAR(36)     NOT NULL,
  topic          VARCHAR(128) NOT NULL,
  operation_id   CHAR(36)     NOT NULL,
  partition_no   INT          NOT NULL,
  consumer_offset BIGINT      NOT NULL,
  outcome        VARCHAR(32)  NOT NULL,
  first_seen_at  DATETIME(3)  NOT NULL,
  last_seen_at   DATETIME(3)  NOT NULL,
  seen_count     INT          NOT NULL DEFAULT 1,
  PRIMARY KEY (event_id),
  KEY idx_consumption_op (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 幂等键账本（上传与问答）
CREATE TABLE idempotency_record (
  id              CHAR(36)     NOT NULL,
  tenant_id       CHAR(36)     NOT NULL,
  actor_id        CHAR(36)     NOT NULL,
  scope           VARCHAR(32)  NOT NULL,
  idempotency_key CHAR(36)     NOT NULL,
  resource_id     CHAR(36)     NOT NULL,
  request_digest  CHAR(64)     NOT NULL,
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (tenant_id, actor_id, scope, idempotency_key),
  KEY idx_idem_resource (resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 审计摘要：只存主体/动作/资源/结果/追踪，不存正文、Prompt、凭据
CREATE TABLE audit_event (
  id            CHAR(36)     NOT NULL,
  tenant_id     CHAR(36)     NULL,
  actor_id      CHAR(36)     NULL,
  action        VARCHAR(64)  NOT NULL,
  resource_type VARCHAR(32)  NOT NULL,
  resource_id   VARCHAR(64)  NULL,
  result        VARCHAR(16)  NOT NULL,
  reason_code   VARCHAR(64)  NULL,
  request_id    CHAR(36)     NULL,
  trace_id      CHAR(36)     NULL,
  source_ip     VARCHAR(64)  NULL,
  metadata_json JSON         NULL,
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_audit_tenant_time (tenant_id, created_at),
  KEY idx_audit_resource_time (resource_type, resource_id, created_at),
  KEY idx_audit_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
