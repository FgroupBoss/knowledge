-- P1-9 重试泵通知水位、审计归档标记与常用运维索引
ALTER TABLE ingest_task
  ADD COLUMN retry_notified_at DATETIME(3) NULL AFTER next_run_at,
  ADD KEY idx_task_retry_pump (status, next_run_at, retry_notified_at);

ALTER TABLE audit_event
  ADD COLUMN archived_at DATETIME(3) NULL AFTER created_at,
  ADD KEY idx_audit_archive (tenant_id, archived_at);

-- outbox 按 topic 排查积压（已有 published_at/created_at 索引，补 topic 维度）
ALTER TABLE outbox_event
  ADD KEY idx_outbox_topic (topic, published_at, created_at);

-- 引擎索引按文档版本反查（删除/重建时使用）
ALTER TABLE engine_document
  ADD KEY idx_engine_doc_ver (document_id, document_ver, searchable);
