#!/usr/bin/env bash
# 生成 P0 验证环境口令（幂等：已存在则跳过，除非 --force）。
set -euo pipefail
cd "$(dirname "$0")/../compose"
mkdir -p secrets
FORCE="${1:-}"

# docker compose 会为缺失的 secret 路径创建空目录，必须先清掉，否则口令文件写不进去。
for d in secrets/*; do
  [[ -d "$d" && "${d##*.}" == "txt" ]] && { rm -rf "$d"; echo "cleanup stale secret dir $d"; }
done

gen_hex() { # name length
  local f="secrets/$1" len="$2"
  if [[ -s "$f" && "$FORCE" != "--force" ]] && ! grep -q $'\r' "$f"; then echo "skip  $f"; return; fi
  # 必须去掉 \r 和 \n：Windows/Git Bash 重定向会写入 CRLF，MinIO 按文件字节算签名，
  # 多一个 \r 就表现为 "request signature ... does not match"。
  openssl rand -hex "$len" | tr -d '\r\n' > "$f"; chmod 600 "$f"; echo "write $f"
}

gen_hex mysql_root_password.txt 16
gen_hex mysql_app_password.txt 16
gen_hex redis_password.txt 16
gen_hex minio_secret_key.txt 24
# 验证夹具口令：只有宿主侧的 app 用，不给 compose；缺失时 07-run-app.sh 会拒绝开启夹具面。
gen_hex fixture_token.txt 16

for pair in "mysql_database.txt:kb_p0" "mysql_app_user.txt:kb_p0_app" "minio_access_key.txt:kbp0admin"; do
  f="secrets/${pair%%:*}"; v="${pair##*:}"
  if [[ ! -s "$f" || "$FORCE" == "--force" ]]; then printf '%s' "$v" > "$f"; chmod 600 "$f"; echo "write $f"; else echo "skip  $f"; fi
done

[[ -f .env ]] || { cp .env.example .env; echo "write .env (from .env.example)"; }

missing=0
for req in mysql_root_password mysql_database mysql_app_user mysql_app_password redis_password minio_access_key minio_secret_key; do
  f="secrets/$req.txt"
  if [[ ! -s "$f" ]]; then echo "!! MISSING $f" >&2; missing=1; fi
done
[[ $missing -eq 0 ]] || { echo "先修复上述 secret 文件再启动 compose" >&2; exit 1; }

echo
echo "secrets ready under p0/compose/secrets/ (mode 600, 不入版本库)"
