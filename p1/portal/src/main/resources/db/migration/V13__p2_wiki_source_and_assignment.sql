-- P2-W1 来源引用与审核人指派（D-06：治理不新增角色枚举，用指派表 + capability）
CREATE TABLE wiki_source (
  id             CHAR(36)     NOT NULL,
  -- 契约表 §4 未列 tenant_id，但 ADR-005 要求多租户表一律带租户边界；跨租户命中在此层就挡住
  tenant_id      CHAR(36)     NOT NULL,
  revision_id    CHAR(36)     NOT NULL,
  -- 来源类型：P1 只有 DOCUMENT；WIKI_PAGE（页面间引用）留待 W3 扩展，取值不加 CHECK
  source_type    VARCHAR(16)  NOT NULL DEFAULT 'DOCUMENT',
  source_id      CHAR(36)     NOT NULL,
  source_version INT          NOT NULL,
  chunk_index    INT          NULL,
  locator        VARCHAR(255) NULL,
  -- 只存来源内容摘要，绝不存正文（红线 1）
  content_digest CHAR(64)     NULL,
  -- 关联来源当时的 knowledge_base.acl_version，读取时回查（D-10）
  acl_version    BIGINT       NOT NULL DEFAULT 0,
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  -- 同一修订里同一来源只出现一次；重复挂接按幂等处理（不产生第二行）
  UNIQUE KEY uk_wiki_source (revision_id, source_type, source_id, source_version),
  KEY idx_wiki_source_doc (source_type, source_id, source_version),
  KEY idx_wiki_source_tenant (tenant_id, revision_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 审核人指派：作用域二选一（WORKSPACE 或 KB），行存在即具备 wiki:review
CREATE TABLE wiki_review_assignment (
  id          CHAR(36)     NOT NULL,
  tenant_id   CHAR(36)     NOT NULL,
  scope_type  VARCHAR(16)  NOT NULL,
  scope_id    CHAR(36)     NOT NULL,
  user_id     CHAR(36)     NOT NULL,
  assigned_by CHAR(36)     NOT NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wiki_assign (tenant_id, scope_type, scope_id, user_id),
  KEY idx_wiki_assign_user (tenant_id, user_id, scope_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
