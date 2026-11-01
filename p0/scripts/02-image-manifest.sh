#!/usr/bin/env bash
# 记录固定版本镜像清单（tag + digest + 来源），产出 p0/compose/image-manifest.txt。
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="compose/image-manifest.txt"
{
  echo "# P0 镜像与依赖锁定清单"
  echo "# generated_at: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "# 约束: 全部固定版本，无 latest 引用；本机镜像加速站对非 library 命名空间返回 403，"
  echo "#       因此 P0 只能使用本机已存在的固定版本镜像（见 decisions/ADR-001）。"
  echo
  echo "[compose images]"
  docker compose -f compose/docker-compose.yml config --images | while read -r img; do
    digest=$(docker image inspect --format '{{range .RepoDigests}}{{.}} {{end}}' "$img" 2>/dev/null || true)
    id=$(docker image inspect --format '{{.Id}}' "$img" 2>/dev/null || echo "MISSING")
    arch=$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$img" 2>/dev/null || echo unknown)
    printf '%-70s arch=%s\n  id=%s\n  repoDigest=%s\n' "$img" "$arch" "$id" "${digest:-none(local-only)}"
  done
  echo
  echo "[toolchain]"
  printf 'jdk          : '; java -version 2>&1 | head -1
  printf 'maven        : '; mvn -v 2>&1 | head -1
  printf 'docker       : '; docker version --format '{{.Server.Version}}'
  printf 'compose      : '; docker compose version --short
  printf 'ollama       : '; ollama --version 2>&1 | tail -1
  echo
  echo "[ollama models]"
  curl -s --max-time 5 http://127.0.0.1:11434/api/tags 2>/dev/null | python -c '
import json,sys
try: d=json.load(sys.stdin)
except Exception: print("  (ollama 未运行)"); raise SystemExit
for m in d.get("models",[]):
    print("  %-24s size=%.2fGB family=%s params=%s quant=%s digest=%s" % (
        m["name"], m["size"]/1e9, m["details"].get("family","-"),
        m["details"].get("parameter_size","-"), m["details"].get("quantization_level","-"),
        m["digest"][:19]))
' || echo "  (ollama 未运行)"
  echo
  echo "[blocked pulls]"
  echo "  docker.io/weknora/*            -> 403 (镜像加速站仅放行 library)"
  echo "  weknora-registry.cn-hangzhou.cr.aliyuncs.com -> EOF/TLS 失败"
  echo "  详见 decisions/ADR-001、ADR-003"
} > "$OUT"
cat "$OUT"
