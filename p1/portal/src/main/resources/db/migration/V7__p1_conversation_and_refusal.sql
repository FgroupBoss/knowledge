-- P1-7 会话、消息、反馈；问答请求与引用的可复现字段
CREATE TABLE conversation (
  id            CHAR(36)     NOT NULL,
  tenant_id     CHAR(36)     NOT NULL,
  workspace_id  CHAR(36)     NULL,
  user_id       CHAR(36)     NOT NULL,
  title         VARCHAR(200) NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_conv_user_time (tenant_id, user_id, created_at),
  KEY idx_conv_ws (workspace_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE chat_message (
  id              CHAR(36)     NOT NULL,
  tenant_id       CHAR(36)     NOT NULL,
  conversation_id CHAR(36)     NOT NULL,
  request_id      CHAR(36)     NULL,
  role            VARCHAR(16)  NOT NULL,
  content         MEDIUMTEXT   NULL,
  status          VARCHAR(24)  NOT NULL,
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_msg_conv_time (conversation_id, created_at),
  KEY idx_msg_request (request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE chat_request
  ADD COLUMN message_id             CHAR(36)    NULL AFTER id,
  ADD COLUMN refusal_reason         VARCHAR(64) NULL AFTER status,
  ADD COLUMN cancelled_at           DATETIME(3) NULL AFTER completed_at,
  ADD COLUMN client_disconnected_at DATETIME(3) NULL AFTER cancelled_at,
  ADD COLUMN last_seq               INT         NOT NULL DEFAULT 0 AFTER client_disconnected_at,
  ADD COLUMN dropped_by_unsupported_citation INT NOT NULL DEFAULT 0 AFTER dropped_by_stale,
  ADD COLUMN model_digest           VARCHAR(64) NULL AFTER engine_model,
  ADD COLUMN prompt_version         VARCHAR(32) NULL AFTER model_digest,
  ADD COLUMN retrieval_config_hash  CHAR(64)    NULL AFTER prompt_version,
  ADD KEY idx_chat_message (message_id);

ALTER TABLE citation
  ADD COLUMN message_id     CHAR(36)     NULL AFTER id,
  ADD COLUMN citation_no    INT          NULL AFTER message_id,
  ADD COLUMN acl_version    BIGINT       NULL AFTER score,
  ADD COLUMN content_digest CHAR(64)     NULL AFTER snippet_digest,
  ADD COLUMN source_locator VARCHAR(512) NULL AFTER content_digest,
  ADD KEY idx_citation_message (message_id, citation_no);

CREATE TABLE chat_feedback (
  id          CHAR(36)     NOT NULL,
  tenant_id   CHAR(36)     NOT NULL,
  message_id  CHAR(36)     NOT NULL,
  actor_id    CHAR(36)     NOT NULL,
  rating      VARCHAR(16)  NOT NULL,
  reason_code VARCHAR(64)  NULL,
  comment     VARCHAR(1000) NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_feedback_message_actor (message_id, actor_id),
  KEY idx_feedback_tenant_time (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
