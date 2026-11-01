-- P1-10 数据修复（可重入）：版本族、引用序号、审计链头
UPDATE document SET family_id = id WHERE family_id IS NULL;

UPDATE citation c
JOIN (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY request_id ORDER BY created_at, id) AS rn
  FROM citation
  WHERE citation_no IS NULL
) ranked ON ranked.id = c.id
SET c.citation_no = ranked.rn
WHERE c.citation_no IS NULL;

INSERT INTO audit_head (tenant_id, last_seq, last_hash, updated_at)
SELECT tenant_id, COALESCE(MAX(seq), 0), NULL, UTC_TIMESTAMP(3)
FROM audit_event
WHERE tenant_id IS NOT NULL
GROUP BY tenant_id
ON DUPLICATE KEY UPDATE last_seq = GREATEST(last_seq, VALUES(last_seq));
