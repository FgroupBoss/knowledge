-- P2-W1 引用与反馈的 Wiki 接缝（expand-only：只加列/放宽可空，不删列不改语义）
-- 注意：把 document_id 放宽为可空在 MySQL 8 走 ALGORITHM=COPY（重建表），不是 INPLACE。
-- pilot 库行数量级小可接受；生产全量库必须在维护窗口或用在线 DDL 工具执行，登记见 V17 的在线 DDL 评估。
ALTER TABLE citation
  ADD COLUMN source_type       VARCHAR(16) NOT NULL DEFAULT 'DOCUMENT' AFTER request_id,
  ADD COLUMN page_id           CHAR(36)    NULL AFTER document_version,
  ADD COLUMN page_revision_no  INT         NULL AFTER page_id,
  MODIFY COLUMN document_id    CHAR(36)    NULL;

-- Wiki 引用的权威回查走 (page_id, page_revision_no)，预览时按页面状态重新授权（D-10）
ALTER TABLE citation
  ADD KEY idx_citation_page (page_id, page_revision_no);

-- 反馈分诊：权威状态在 correction_case，这里只冗余一个筛选位，避免治理视图跨表 join
ALTER TABLE chat_feedback
  ADD COLUMN triaged_case_id CHAR(36)    NULL AFTER reason_code,
  ADD COLUMN status          VARCHAR(24) NOT NULL DEFAULT 'NEW' AFTER triaged_case_id,
  ADD KEY idx_feedback_triage (tenant_id, status, created_at);
