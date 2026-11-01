-- P2-W1 数据修复（可重入：每条语句的 WHERE 就是"还有多少行要修"，修完再跑影响 0 行）
-- dry-run 口径：把下面三条 UPDATE 的 SET 换成 COUNT(*)、WHERE 原样保留即可先看影响面，
-- 例：SELECT COUNT(*) FROM wiki_revision WHERE body_digest <> LOWER(SHA2(body, 256));
-- 只在指针/摘要这类"派生一致性"字段上回填，不改 status、不改 updated_at、不动 acl_version
-- （治理位由状态机边驱动，修复脚本越权改状态会绕开 `WHERE status = 前置态` 的乐观锁语义）。

-- 1) current_revision_no 回填到该页最大修订号
UPDATE wiki_page p
JOIN (
  SELECT page_id, MAX(revision_no) AS max_no
  FROM wiki_revision
  GROUP BY page_id
) r ON r.page_id = p.id
SET p.current_revision_no = r.max_no
WHERE p.current_revision_no < r.max_no;

-- 2) 已发布页面的可见性指针补齐到最新 APPROVED 修订（缺指针会让引用预览回查不到正文）
UPDATE wiki_page p
JOIN (
  SELECT page_id, MAX(revision_no) AS max_no
  FROM wiki_revision
  WHERE status = 'APPROVED'
  GROUP BY page_id
) a ON a.page_id = p.id
JOIN wiki_revision rev ON rev.page_id = a.page_id AND rev.revision_no = a.max_no
SET p.published_revision_id = rev.id
WHERE p.status = 'PUBLISHED' AND p.published_revision_id IS NULL;

-- 3) 正文摘要补齐与重算：空摘要、或摘要与 MySQL 侧 SHA2 不符（Java 侧 Hashing.sha256Hex 按 UTF-8
-- 计算，与 utf8mb4 列的 SHA2 字节序列一致，故可直接比对）
UPDATE wiki_revision
SET body_digest = LOWER(SHA2(body, 256))
WHERE body_digest IS NULL OR body_digest = '' OR body_digest <> LOWER(SHA2(body, 256));
