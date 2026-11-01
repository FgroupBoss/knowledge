-- P1-6 文档版本族与索引重建元数据
-- 保留“一行一个版本”的语义：family_id 标识同一逻辑文档，引用继续绑定 document_id + version。
ALTER TABLE document
  ADD COLUMN family_id       CHAR(36)    NULL AFTER id,
  ADD COLUMN supersedes_id   CHAR(36)    NULL AFTER family_id,
  ADD COLUMN retention_until DATETIME(3) NULL AFTER acl_enforced,
  ADD COLUMN deleted_at      DATETIME(3) NULL AFTER retention_until,
  ADD COLUMN parser_version  VARCHAR(64) NULL AFTER deleted_at,
  ADD COLUMN chunker_version VARCHAR(64) NULL AFTER parser_version,
  ADD COLUMN embedding_model VARCHAR(64) NULL AFTER chunker_version,
  ADD COLUMN embedding_dim   INT         NULL AFTER embedding_model,
  ADD COLUMN index_version   VARCHAR(64) NULL AFTER embedding_dim,
  ADD KEY idx_doc_family (family_id, version),
  ADD KEY idx_doc_retention (retention_until, deleted_at);

-- 先回填再收紧非空，避免在已有行上直接加 NOT NULL 失败。
UPDATE document SET family_id = id WHERE family_id IS NULL;

ALTER TABLE document
  MODIFY COLUMN family_id CHAR(36) NOT NULL,
  ADD UNIQUE KEY uk_doc_family_version (family_id, version);
