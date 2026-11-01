#!/usr/bin/env bash
# 启动 P0 基础设施（MySQL/Redis/Kafka/MinIO），等待健康检查，建 Kafka 主题。
set -euo pipefail
cd "$(dirname "$0")/../compose"
../scripts/00-gen-secrets.sh
[[ -f .env ]] || cp .env.example .env

echo "==> docker network 检查"
if ! docker network inspect kb-p0_p0-backend >/dev/null 2>&1; then
  echo "    p0-backend 将由 compose 创建 (internal=true)"
fi

echo "==> compose up -d"
docker compose up -d

echo "==> 等待健康检查 (最多 240s)"
deadline=$((SECONDS + 240))
while :; do
  unhealthy=$(docker compose ps --format '{{.Service}} {{.Status}}' | awk '$1!="minio-init"' | grep -viE 'healthy|running|exited \(0\)' || true)
  if [[ -z "$unhealthy" ]]; then break; fi
  if (( SECONDS > deadline )); then
    echo "!! 超时，未就绪服务:"; echo "$unhealthy"; docker compose ps; exit 1
  fi
  sleep 5
done
docker compose ps
echo "==> internal 网络确认（容器应无外网路由）"
docker network inspect kb-p0_p0-backend --format 'name={{.Name}} internal={{.Internal}}'
echo "==> 创建 Kafka 主题"
../scripts/03-topics.sh
echo "==> 栈已就绪"
