-- 审计链完整性：metadata_json 必须按写入字节参与哈希。
-- JSON 列会被 MySQL 规范化（补空格、重排键），读回值与写入值不一致会破坏 record_hash 校验，
-- 因此改为文本列保存规范化后的 JSON 字符串；查询侧仍可用 CAST(... AS JSON)。
ALTER TABLE audit_event
  MODIFY COLUMN metadata_json MEDIUMTEXT NULL;
