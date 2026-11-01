#!/usr/bin/env bash
# 在宿主机启动 P0 门户，连接 Compose 暴露在 127.0.0.1 的依赖端口。
# 凭据只从 compose/secrets/*.txt 读入环境变量，脚本不打印任何值；缺文件即失败，绝不退回空口令。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
SEK="$ROOT/compose/secrets"
read_secret() { tr -d '\r\n' < "$SEK/$1"; }

for f in mysql_database.txt mysql_app_user.txt mysql_app_password.txt redis_password.txt \
         minio_access_key.txt minio_secret_key.txt; do
  [[ -s "$SEK/$f" ]] || { echo "!! 缺少 $SEK/$f — 先执行 00-gen-secrets.sh 与 01-stack-up.sh" >&2; exit 1; }
done

export P0_DB_URL="jdbc:mysql://127.0.0.1:13306/$(read_secret mysql_database.txt)?useSSL=false&requireSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&allowPublicKeyRetrieval=true&connectionTimeZone=%2B00:00"
export P0_DB_USER="$(read_secret mysql_app_user.txt)"
export P0_DB_PASSWORD="$(read_secret mysql_app_password.txt)"
export P0_REDIS_HOST=127.0.0.1
export P0_REDIS_PORT=16379
export P0_REDIS_PASSWORD="$(read_secret redis_password.txt)"
export P0_KAFKA_BOOTSTRAP=localhost:29092
export P0_MINIO_ENDPOINT=http://127.0.0.1:19000
export P0_MINIO_ACCESS_KEY="$(read_secret minio_access_key.txt)"
export P0_MINIO_SECRET_KEY="$(read_secret minio_secret_key.txt)"
export P0_APP_PORT="${P0_APP_PORT:-18180}"

# 验证夹具：越权矩阵需要它造租户/成员/ACL 样本；口令与身份头分属两把钥匙，缺一个都不开面。
if [[ "${P0_FIXTURE_ENABLED:-true}" == "true" ]]; then
  [[ -s "$SEK/fixture_token.txt" ]] || {
    echo "!! 夹具已请求开启，但缺少 $SEK/fixture_token.txt（用 P0_FIXTURE_ENABLED=false 可关闭夹具）" >&2
    exit 1
  }
  export P0_FIXTURE_ENABLED=true
  export P0_FIXTURE_TOKEN="$(read_secret fixture_token.txt)"
fi

echo "==> 启动 kb-p0-portal :$P0_APP_PORT (db 13306 / redis 16379 / kafka 29092 / minio 19000)"
echo "    fixture=${P0_FIXTURE_ENABLED:-false} engine=${P0_ENGINE_PROVIDER:-stub} offline=${P0_OFFLINE_ENFORCED:-false}"
cd "$ROOT/portal-poc"
exec mvn -B -o spring-boot:run
