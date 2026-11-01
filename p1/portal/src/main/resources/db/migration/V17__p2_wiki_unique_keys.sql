-- P2-W1 收紧唯一键（在线 DDL 评估：InnoDB 加唯一索引走 INPLACE + LOCK=NONE，
-- 但已有重复值会让迁移直接失败——这是有意的：宁缺不dup，绝不允许"两份有效可见性"或"同 slug 两页"落库。
-- V12–V16 与本版在同一次 Flyway 执行里完成，落表时这几张表都是空表；若在已有数据的库上重放，先跑 V18 的 dry-run 排查。）
ALTER TABLE wiki_revision
  ADD UNIQUE KEY uk_wiki_rev_page_no (page_id, revision_no);

ALTER TABLE wiki_reminder
  ADD UNIQUE KEY uk_wiki_remind_bucket (page_id, rule, date_bucket);

-- slug 在知识库作用域内唯一（D-06：归属已有知识库，不新建 ACL 模型）；冲突由门户转成
-- VALIDATION_FAILED(400)，不返回对方标题
ALTER TABLE wiki_page
  ADD UNIQUE KEY uk_wiki_page_kb_slug (kb_id, slug);
