-- P2-W1 Wiki 页面主体与修订历史（`p1/contracts/state-machine-wiki.md` 的 PAGE / REVISION 两轴）
-- 唯一键 (kb_id, slug) 与 (page_id, revision_no) 按 §4 版本划分推迟到 V17；
-- 此处只建普通索引，保证空库与已有行都能先落表再收紧约束。
CREATE TABLE wiki_page (
  id                    CHAR(36)     NOT NULL,
  tenant_id             CHAR(36)     NOT NULL,
  workspace_id          CHAR(36)     NOT NULL,
  kb_id                 CHAR(36)     NOT NULL,
  slug                  VARCHAR(120) NOT NULL,
  title                 VARCHAR(255) NOT NULL,
  -- 审核态：DRAFT / IN_REVIEW / PUBLISHED / RETRACTING / RETRACTED / ARCHIVED
  status                VARCHAR(24)  NOT NULL,
  -- 新鲜度是派生态（FRESH / STALE / EXPIRED），无入边无出边，绝不与 status 混写
  freshness             VARCHAR(16)  NOT NULL DEFAULT 'FRESH',
  owner_user_id         CHAR(36)     NOT NULL,
  current_revision_no   INT          NOT NULL DEFAULT 0,
  published_revision_id CHAR(36)     NULL,
  valid_until           DATETIME(3)  NULL,
  review_at             DATETIME(3)  NULL,
  -- 页面级乐观锁计数，由治理变更/提交/审核递增；与 knowledge_base.acl_version 无关。
  -- 设计文档 §4 散文写作 acl_version_guard，按「契约文件 > 文档散文」以 error-codes.md
  -- 的产生点列名 wiki_page.acl_version 为准（裁定见 ADR-010）。
  acl_version           BIGINT       NOT NULL DEFAULT 0,
  created_at            DATETIME(3)  NOT NULL,
  updated_at            DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_wiki_page_kb (kb_id, status),
  KEY idx_wiki_page_tenant_status (tenant_id, status),
  KEY idx_wiki_page_owner (owner_user_id, status),
  -- 新鲜度扫描的游标：只按 (freshness, review_at, valid_until) 取候选，不做全表 UPDATE
  KEY idx_wiki_page_fresh (freshness, review_at, valid_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 修订历史：一行一个修订，正文权威源在 MySQL（D-01），MinIO 不存 Wiki 正文
CREATE TABLE wiki_revision (
  id                   CHAR(36)     NOT NULL,
  page_id              CHAR(36)     NOT NULL,
  revision_no          INT          NOT NULL,
  author_user_id       CHAR(36)     NOT NULL,
  -- 修订态：DRAFT / PENDING / APPROVED / REJECTED / SUPERSEDED
  status               VARCHAR(16)  NOT NULL,
  body                 MEDIUMTEXT   NOT NULL,
  body_digest          CHAR(64)     NOT NULL,
  summary              VARCHAR(512) NULL,
  reviewer_user_id     CHAR(36)     NULL,
  reviewed_at          DATETIME(3)  NULL,
  -- 审核意见原文只进站内通知与响应的即时字段，库里留摘要哈希（红线 1）
  decision_note_digest CHAR(64)     NULL,
  parent_revision_id   CHAR(36)     NULL,
  created_at           DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_wiki_rev_page (page_id, revision_no),
  KEY idx_wiki_rev_author (author_user_id, created_at),
  -- 审核队列按状态取待办
  KEY idx_wiki_rev_queue (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
