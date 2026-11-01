#!/usr/bin/env bash
# 显式创建主题（禁用 auto-create），按业务主键分区保证同对象严格有序。
# P0 四个入库/删除主题 + P2 Wiki 三个主题（schema 已冻结，生产方 W2 接线）。
# kb.audit.v1 不在此脚本内：它是 P1 试点栈的审计外发主题，建题命令见 `p1/compose/README.md` 第 2 节。
set -euo pipefail
# Git Bash 会把容器内绝对路径转换成本地路径，必须关闭 MSYS 路径转换。
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'
BOOT=kafka:9092
kt() { docker exec -e MSYS_NO_PATHCONV=1 kb-p0-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$BOOT" "$@"; }

for spec in "kb.ingest.requested.v1:3" "kb.ingest.retry.v1:3" "kb.ingest.dlq.v1:1" "kb.document.delete.v1:3" \
            "kb.wiki.sync.v1:3" "kb.wiki.retract.v1:3" "kb.wiki.dlq.v1:1"; do
  topic=${spec%%:*}; parts=${spec##*:}
  out=$(kt --create --topic "$topic" --partitions "$parts" --replication-factor 1 \
        --config min.insync.replicas=1 --config retention.ms=259200000 2>&1) || true
  if grep -q "already exists" <<<"$out"; then echo "exists : $topic"; else echo "created: $topic (${out:-ok})"; fi
done
echo "==> topics"
kt --list
echo "==> kb.ingest.requested.v1 describe"
kt --describe --topic kb.ingest.requested.v1
