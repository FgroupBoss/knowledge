-- P0-1 身份与授权边界（MySQL 8.4，UTC，UUID 以 CHAR(36) 规范字符串存储，见 ADR-005）
CREATE TABLE tenant (
  id            CHAR(36)     NOT NULL,
  code          VARCHAR(64)  NOT NULL,
  name          VARCHAR(128) NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 身份主键是 (issuer, subject)，邮箱等可变属性不作身份键
CREATE TABLE app_user (
  id            CHAR(36)     NOT NULL,
  tenant_id     CHAR(36)     NOT NULL,
  issuer        VARCHAR(255) NOT NULL,
  subject       VARCHAR(128) NOT NULL,
  display_name  VARCHAR(128) NOT NULL,
  clearance     VARCHAR(16)  NOT NULL DEFAULT 'INTERNAL',
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_issuer_subject (issuer, subject),
  KEY idx_user_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workspace (
  id            CHAR(36)     NOT NULL,
  tenant_id     CHAR(36)     NOT NULL,
  code          VARCHAR(64)  NOT NULL,
  name          VARCHAR(128) NOT NULL,
  owner_user_id CHAR(36)     NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_ws_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE workspace_member (
  id            CHAR(36)    NOT NULL,
  workspace_id  CHAR(36)    NOT NULL,
  user_id       CHAR(36)    NOT NULL,
  role          VARCHAR(16) NOT NULL,
  created_at    DATETIME(3) NOT NULL,
  updated_at    DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_member (workspace_id, user_id),
  KEY idx_member_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- engine_kb_id 是远端引用；acl_version 每次成员/ACL 变更递增，用于缓存失效与事件校验
CREATE TABLE knowledge_base (
  id              CHAR(36)     NOT NULL,
  tenant_id       CHAR(36)     NOT NULL,
  workspace_id    CHAR(36)     NOT NULL,
  code            VARCHAR(64)  NOT NULL,
  name            VARCHAR(128) NOT NULL,
  engine          VARCHAR(32)  NOT NULL DEFAULT 'stub',
  engine_kb_id    VARCHAR(128) NULL,
  classification  VARCHAR(16)  NOT NULL DEFAULT 'INTERNAL',
  acl_version     BIGINT       NOT NULL DEFAULT 1,
  status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME(3)  NOT NULL,
  updated_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_kb_ws_code (workspace_id, code),
  KEY idx_kb_tenant_ws (tenant_id, workspace_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 文档级 ACL（对 P0 最小表集的补充，见 ADR-006）：可把可见范围收紧到指定主体
CREATE TABLE document_acl (
  id            CHAR(36)     NOT NULL,
  tenant_id     CHAR(36)     NOT NULL,
  document_id   CHAR(36)     NOT NULL,
  subject_type  VARCHAR(16)  NOT NULL DEFAULT 'USER',
  subject_id    CHAR(36)     NOT NULL,
  grant_type    VARCHAR(16)  NOT NULL,
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_acl (document_id, subject_type, subject_id, grant_type),
  KEY idx_doc_acl_doc (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
