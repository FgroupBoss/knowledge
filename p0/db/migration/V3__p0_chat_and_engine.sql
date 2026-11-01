-- P0-3 问答请求、引用与（参考引擎的）索引存储
CREATE TABLE chat_request (
  id               CHAR(36)     NOT NULL,
  tenant_id        CHAR(36)     NOT NULL,
  workspace_id     CHAR(36)     NOT NULL,
  actor_id         CHAR(36)     NOT NULL,
  kb_id            CHAR(36)     NOT NULL,
  conversation_id  CHAR(36)     NOT NULL,
  acl_version      BIGINT       NOT NULL,
  idempotency_key  CHAR(36)     NULL,
  status           VARCHAR(24)  NOT NULL,
  question         VARCHAR(4000) NOT NULL,
  answer           MEDIUMTEXT   NULL,
  engine_model     VARCHAR(128) NULL,
  prompt_chunks    INT          NOT NULL DEFAULT 0,
  engine_candidates INT         NOT NULL DEFAULT 0,
  dropped_by_acl   INT          NOT NULL DEFAULT 0,
  dropped_by_clearance INT      NOT NULL DEFAULT 0,
  dropped_by_stale INT          NOT NULL DEFAULT 0,
  authorization_ms BIGINT       NOT NULL DEFAULT 0,
  retrieval_ms     BIGINT       NOT NULL DEFAULT 0,
  first_token_ms   BIGINT       NULL,
  total_ms         BIGINT       NOT NULL DEFAULT 0,
  prompt_tokens    INT          NULL,
  completion_tokens INT         NULL,
  error_code       VARCHAR(64)  NULL,
  trace_id         CHAR(36)     NOT NULL,
  created_at       DATETIME(3)  NOT NULL,
  completed_at     DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_chat_idem (tenant_id, actor_id, idempotency_key),
  KEY idx_chat_conv (conversation_id, created_at),
  KEY idx_chat_tenant_time (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 引用只能由检索结果生成；预览时按 document_id+version 重新授权
CREATE TABLE citation (
  id               CHAR(36)     NOT NULL,
  tenant_id        CHAR(36)     NOT NULL,
  request_id       CHAR(36)     NOT NULL,
  document_id      CHAR(36)     NOT NULL,
  document_version INT          NOT NULL,
  kb_id            CHAR(36)     NOT NULL,
  engine_doc_id    VARCHAR(128) NOT NULL,
  engine_chunk_id  VARCHAR(128) NOT NULL,
  title            VARCHAR(255) NULL,
  page_no          INT          NULL,
  section          VARCHAR(255) NULL,
  char_start       INT          NULL,
  char_end         INT          NULL,
  snippet          MEDIUMTEXT   NOT NULL,
  snippet_digest   CHAR(64)     NOT NULL,
  score            DOUBLE       NOT NULL DEFAULT 0,
  acl_verified     TINYINT(1)   NOT NULL DEFAULT 1,
  created_at       DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_citation_request (request_id),
  KEY idx_citation_doc (document_id, document_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 以下三张表是“参考知识引擎”的内部存储代理（对应 WeKnora 的引擎侧库），
-- 业务代码只通过 KnowledgeEnginePort 访问，绝不从 Java 侧 SQL 改写这些表。
CREATE TABLE engine_kb (
  engine_kb_id   VARCHAR(128) NOT NULL,
  tenant_id      CHAR(36)     NOT NULL,
  workspace_id   CHAR(36)     NOT NULL,
  kb_id          CHAR(36)     NOT NULL,
  classification VARCHAR(16)  NOT NULL,
  acl_version    BIGINT       NOT NULL,
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (engine_kb_id),
  UNIQUE KEY uk_engine_kb_kb (kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE engine_document (
  engine_doc_id  VARCHAR(128) NOT NULL,
  engine_kb_id   VARCHAR(128) NOT NULL,
  tenant_id      CHAR(36)     NOT NULL,
  workspace_id   CHAR(36)     NOT NULL,
  kb_id          CHAR(36)     NOT NULL,
  document_id    CHAR(36)     NOT NULL,
  document_ver   INT          NOT NULL,
  sha256         CHAR(64)     NOT NULL,
  searchable     TINYINT(1)   NOT NULL DEFAULT 1,
  parse_meta     JSON         NULL,
  indexed_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (engine_doc_id),
  UNIQUE KEY uk_engine_doc (document_id, document_ver),
  KEY idx_engine_doc_kb (engine_kb_id, searchable)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 元数据列冗余保存 tenant/ws/kb/doc/classification，使“召回前过滤”与“逐条复核”都能验证
CREATE TABLE engine_chunk (
  id             CHAR(36)     NOT NULL,
  engine_chunk_id VARCHAR(128) NOT NULL,
  engine_doc_id  VARCHAR(128) NOT NULL,
  engine_kb_id   VARCHAR(128) NOT NULL,
  tenant_id      CHAR(36)     NOT NULL,
  workspace_id   CHAR(36)     NOT NULL,
  kb_id          CHAR(36)     NOT NULL,
  document_id    CHAR(36)     NOT NULL,
  document_ver   INT          NOT NULL,
  classification VARCHAR(16)  NOT NULL,
  chunk_no       INT          NOT NULL,
  page_no        INT          NULL,
  section        VARCHAR(255) NULL,
  char_start     INT          NULL,
  char_end       INT          NULL,
  content        MEDIUMTEXT   NOT NULL,
  content_digest CHAR(64)     NOT NULL,
  embedding      LONGBLOB     NULL,
  embedding_model VARCHAR(64) NULL,
  token_count    INT          NOT NULL DEFAULT 0,
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_engine_chunk (engine_doc_id, chunk_no),
  KEY idx_chunk_kb (engine_kb_id),
  KEY idx_chunk_doc (document_id, document_ver),
  KEY idx_chunk_tenant (tenant_id, workspace_id, kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
