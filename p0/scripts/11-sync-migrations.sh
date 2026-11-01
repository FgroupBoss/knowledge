#!/usr/bin/env bash
# 把 Flyway 迁移（真正执行的那份）镜像到评审副本，作为交付物与 DBA 评审用。
# 只做单向复制并逐文件核对 SHA-256：副本与执行源不一致时宁可失败，也不留"看起来一样"的隐患。
#
# 默认仍是 P0 的 portal-poc → p0/db/migration（历史语义不变）。
# P1/P2 复用同一套判定：用 SRC_DIR / DST_DIR 覆盖，见 `p1/scripts/30-sync-migrations.sh`。
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${SRC_DIR:-$(cd "$HERE/../portal-poc/src/main/resources/db/migration" && pwd)}"
DST="${DST_DIR:-$HERE/../db/migration}"
[[ -d "$SRC" ]] || { echo "!! 执行源目录不存在：$SRC" >&2; exit 1; }

mkdir -p "$DST"
changed=0
for f in "$SRC"/*.sql; do
  base="$(basename "$f")"
  if ! cmp -s "$f" "$DST/$base"; then
    cp "$f" "$DST/$base"
    changed=$((changed + 1))
    echo "同步 $base"
  fi
done

# 目标目录里不属于当前迁移序列的文件必须显式列出，避免旧版本 DDL 混进评审包
for f in "$DST"/*.sql; do
  base="$(basename "$f")"
  [[ -e "$SRC/$base" ]] || { echo "!! 副本中有多出的迁移：$base（应删除或回到源目录）" >&2; exit 1; }
done

fail=0
while IFS= read -r f; do
  base="$(basename "$f")"
  a="$(sha256sum "$f" | cut -d' ' -f1)"
  b="$(sha256sum "$DST/$base" | cut -d' ' -f1)"
  [[ "$a" == "$b" ]] || { echo "!! 校验和不一致：$base" >&2; fail=1; }
  printf '%-40s %s\n' "$base" "${a:0:16}"
done < <(find "$SRC" -maxdepth 1 -name '*.sql' | sort)

[[ $fail -eq 0 ]] || exit 1
echo "==> $DST 与执行源一致（本次更新 $changed 个文件）"

