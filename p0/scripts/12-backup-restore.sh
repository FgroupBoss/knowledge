#!/usr/bin/env bash
# 备份与恢复演练：mysqldump（单事务一致性快照）+ 原件整桶镜像，然后把备份真的导入回一个临时库并核对。
# 只写"备份能不能用"，不写"备份文件在不在"。全程口令走环境变量，不出现在命令行、日志或报告里；
# 对象存储 key 也不外传，报告里只出现内容哈希。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
SEK="$ROOT/compose/secrets"
REPORT="$ROOT/evaluation/reports/backup-restore.md"
read_secret() { tr -d '\r\n' < "$SEK/$1"; }
MYSQL_ROOT_PWD="$(read_secret mysql_root_password.txt)"
DB="$(read_secret mysql_database.txt)"
AK="$(read_secret minio_access_key.txt)"
SK="$(read_secret minio_secret_key.txt)"
BUCKET="$(grep -oP '^P0_MINIO_BUCKET=\K.*' "$ROOT/compose/.env")"
MC_IMAGE="minio/mc@sha256:a7fe349ef4bd8521fb8497f55c6042871b2ae640607cf99d9bede5e9bdf11727"
RESTORE_DB="kb_p0_restore"

# 口令只进子进程环境，不进命令行
mysqlq() { (cd "$ROOT/compose" && MYSQL_PWD="$MYSQL_ROOT_PWD" docker compose exec -T -e MYSQL_PWD mysql \
              mysql -N -B -uroot "$1" -e "$2") | tr -d '\r'; }
mysqld() { (cd "$ROOT/compose" && MYSQL_PWD="$MYSQL_ROOT_PWD" docker compose exec -T -e MYSQL_PWD mysql \
              mysql -uroot -e "$1") > /dev/null; }

TABLES=(tenant app_user workspace workspace_member knowledge_base document ingest_task outbox_event
        event_consumption idempotency_record audit_event chat_request citation engine_kb engine_document engine_chunk)
TS="$(date -u +%Y%m%dT%H%M%SZ)"
DEST="$ROOT/.tmp/backup/$TS"
mkdir -p "$DEST/objects"
mismatch=0
hash_mismatch=0

{
  echo "# P0 备份与恢复演练"
  echo ""
  echo "- 时间（UTC）：$TS"
  echo "- 备份落点：\`p0/.tmp/backup/$TS\`（交付物只含本报告，数据留在本机）"
  echo "- 方式：\`mysqldump --single-transaction\`（不锁业务写） + \`mc mirror\` 整桶原件"
  echo ""

  (cd "$ROOT/compose" && MYSQL_PWD="$MYSQL_ROOT_PWD" docker compose exec -T -e MYSQL_PWD mysql \
      mysqldump -uroot --single-transaction --set-gtid-purged=OFF --routines \
      --default-character-set=utf8mb4 "$DB") | gzip > "$DEST/db.sql.gz"
  gzip -t "$DEST/db.sql.gz"
  echo "- 数据库快照写出并通过 gzip 完整性自检：$(du -h "$DEST/db.sql.gz" | cut -f1)"

  # Docker Desktop 只认 Windows 形态的 bind mount 源路径，Git Bash 的 /e/... 会静默挂出空目录
  MOUNT_SRC="$(cygpath -w "$DEST/objects")"
  docker run --rm --network kb-p0_p0-backend -e P0_AK="$AK" -e P0_SK="$SK" -e P0_BUCKET="$BUCKET" \
    -v "$MOUNT_SRC:/backup/objects" --entrypoint sh "$MC_IMAGE" -c \
    'mc alias set p0 http://minio:9000 "$P0_AK" "$P0_SK" >/dev/null
     mc mirror --overwrite "p0/$P0_BUCKET" /backup/objects >/dev/null 2>&1 && echo "mirror ok" || echo "mirror FAILED"' \
    > "$DEST/mirror.log" 2>&1
  grep -q "mirror ok" "$DEST/mirror.log" || echo "- !! 对象镜像未报告成功，见 p0/.tmp/backup/$TS/mirror.log"
  objs=$(find "$DEST/objects" -type f | wc -l | tr -d ' ')
  size=$(du -sh "$DEST/objects" | cut -f1)
  db_objs=$(mysqlq "$DB" "SELECT COUNT(*) FROM document WHERE status<>'DELETED'")
  echo "- 原件镜像：$objs 个对象 / $size；库里非 DELETED 文档 $db_objs 行"
  echo ""

  mysqld "DROP DATABASE IF EXISTS $RESTORE_DB; CREATE DATABASE $RESTORE_DB DEFAULT CHARSET utf8mb4;"
  gunzip -c "$DEST/db.sql.gz" | (cd "$ROOT/compose" && MYSQL_PWD="$MYSQL_ROOT_PWD" docker compose exec -T \
      -e MYSQL_PWD mysql mysql -uroot --default-character-set=utf8mb4 "$RESTORE_DB") > /dev/null
  echo "- 备份已导入临时库 \`$RESTORE_DB\`（运行库零写入）"
  echo ""
  echo "| 表 | 运行库行数 | 恢复库行数 | 一致 |"
  echo "| --- | --- | --- | --- |"
  for t in "${TABLES[@]}"; do
    a=$(mysqlq "$DB" "SELECT COUNT(*) FROM \`$t\`")
    b=$(mysqlq "$RESTORE_DB" "SELECT COUNT(*) FROM \`$t\`")
    if [[ "$a" == "$b" ]]; then flag=✅; else flag=❌; mismatch=$((mismatch + 1)); fi
    echo "| $t | $a | $b | $flag |"
  done

  echo ""
  echo "## 原件与元数据对账（最近 5 份 READY 文档，按内容哈希）"
  echo ""
  echo "| 文档 | 版本 | 库内 sha256 | 备份对象 | 一致 |"
  echo "| --- | --- | --- | --- | --- |"
  all_hash=$(find "$DEST/objects" -type f -exec sha256sum {} \; | awk '{print $1}' | sort -u)
  while IFS=$'\t' read -r name ver want; do
    [[ -n "$want" ]] || continue
    if grep -q "^$want$" <<< "$all_hash"; then
      echo "| $name | $ver | ${want:0:16}… | 命中同一内容 | ✅ |"
    else
      echo "| $name | $ver | ${want:0:16}… | 未找到 | ❌ |"
      hash_mismatch=$((hash_mismatch + 1))
    fi
  done < <(mysqlq "$DB" "SELECT display_name, version, sha256 FROM document WHERE status='READY' \
                     ORDER BY updated_at DESC LIMIT 5")

  mysqld "DROP DATABASE IF EXISTS $RESTORE_DB;"
  echo ""
  echo "## 结论"
  echo ""
  echo "- 表级行数差异 **$mismatch** 项；原件内容哈希未匹配 **$hash_mismatch** 项。"
  echo "- 临时库已删除；对象 key 未出现在本报告，只用内容哈希对账（key 改名也不影响可还原性）。"
  if (( mismatch == 0 && hash_mismatch == 0 )); then
    echo "- **判定：通过**——备份可恢复，且「原件 + 元数据」足以重建业务库与索引。"
  else
    echo "- **判定：不通过**——存在行数或哈希差异，必须在 P1 前修流程。"
  fi
  echo ""
  echo "## 已知限制（如实记录）"
  echo ""
  echo "- 本次是同机旁路恢复，不是跨机灾难恢复：主机/磁盘级故障下的 RPO/RTO 实测仍缺（P1）。"
  echo "- 备份落在工作目录内；生产必须写独立介质并验证不可变保留与加密。"
  echo "- 引擎索引未单独备份，按设计它是可从原件重建的派生物，重建路径由 13-fault-drill 的删除→重投演练验证。"
} > "$REPORT"
cat "$REPORT"
[[ $mismatch -eq 0 && $hash_mismatch -eq 0 ]]
