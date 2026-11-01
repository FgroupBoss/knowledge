-- P1-4 身份域映射、租户内业务码唯一、审计哈希链
-- 身份键仍是 (issuer, subject)；租户内唯一约束只约束同一租户下的 subject，防止两个租户同名互相覆盖业务查询。

ALTER TABLE app_user
  ADD UNIQUE KEY uk_user_tenant_subject (tenant_id, subject);

CREATE TABLE identity_provider (
  id            CHAR(36)     NOT NULL,
  tenant_id     CHAR(36)     NOT NULL,
  issuer        VARCHAR(255) NOT NULL,
  client_id     VARCHAR(128) NULL,
  jit_enabled   TINYINT(1)   NOT NULL DEFAULT 0,
  enabled       TINYINT(1)   NOT NULL DEFAULT 1,
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_idp_issuer (issuer),
  KEY idx_idp_tenant (tenant_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE audit_event
  ADD COLUMN seq        BIGINT      NULL AFTER id,
  ADD COLUMN prev_hash  CHAR(64)    NULL AFTER metadata_json,
  ADD COLUMN record_hash CHAR(64)   NULL AFTER prev_hash,
  ADD KEY idx_audit_tenant_seq (tenant_id, seq);

CREATE TABLE audit_head (
  tenant_id   CHAR(36)    NOT NULL,
  last_seq    BIGINT      NOT NULL DEFAULT 0,
  last_hash   CHAR(64)    NULL,
  updated_at  DATETIME(3) NOT NULL,
  PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
