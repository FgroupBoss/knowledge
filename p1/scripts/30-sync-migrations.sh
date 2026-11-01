#!/usr/bin/env bash
# P1/P2 迁移评审副本：把 Flyway 执行源 p1/portal/src/main/resources/db/migration
# 镜像到 p1/db/migration，并逐文件核对 SHA-256。
#
# 判定语义与 `p0/scripts/11-sync-migrations.sh` 完全相同（同一份实现，只是换了源与目标）：
# 单向复制、副本多出文件即失败、校验和不一致即非零退出。**不复制**、**不改写**历史版本。
#
# 为什么 P1 也要一份副本：P0 只有 `p0/db/migration`，P1 的 V1–V11 长期没有评审副本，
# DBA 只能去翻源码目录（backlog 登记项）。P2 的 V12–V18 一并纳管。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"

export SRC_DIR="$(cd "$ROOT/p1/portal/src/main/resources/db/migration" && pwd)"
export DST_DIR="${DST_DIR:-$ROOT/p1/db/migration}"

bash "$ROOT/p0/scripts/11-sync-migrations.sh"
