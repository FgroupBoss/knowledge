# 授权切片与 ACL 负向矩阵（自动验证）

- 生成时间：2026-11-01 01:09:57
- 目标服务：http://127.0.0.1:18180（fixture=enabled）
- 结论：**26/26 条通过，越权读取成功 0（硬门达成） 例**

| 用例 | 断言 | 结果 | 证据 |
| --- | --- | --- | --- |
| ID-01 | 缺少主体头必须 401，绝不按匿名继续执行 | PASS | 401/UNAUTHENTICATED |
| ID-02 | 未供给的主体必须 401（不自动建号） | PASS | 401/UNAUTHENTICATED |
| ID-03 | 主体头格式非法必须 401 | PASS | 401 |
| ID-04 | 引导管理员可见其空间与 KB | PASS | 200 tenant=red ws=4 |
| FX-01 | 夹具端点缺口令即拒绝 | PASS | 403 |
| FX-02 | 取证端点仅空间 OWNER/ADMIN 可用 | PASS | 403/ACCESS_DENIED |
| XT-01 | 跨租户读文档 404 且不泄露存在性 | PASS | 404/RESOURCE_NOT_FOUND |
| XT-02 | 跨租户上传 404 | PASS | 404 |
| XT-03 | 跨租户改成员 404（无法自授权） | PASS | 404 |
| XT-04 | 跨租户访问全部落审计 CROSS_TENANT_ACCESS | PASS | audit CROSS_TENANT_ACCESS rows for mallory=4 |
| RB-01 | VIEWER 无 kb:write，上传被拒 | PASS | 403/ACCESS_DENIED |
| RB-02 | VIEWER 无 member:manage，不能自我提权 | PASS | 403/ACCESS_DENIED |
| RB-03 | CONTRIBUTOR 无 kb:delete，删除被拒 | PASS | 403/ACCESS_DENIED |
| RB-04 | CONTRIBUTOR 可写即可读，正向路径成立 | PASS | 200/ |
| CL-01 | INTERNAL 主体读 SECRET 文档必须 CLEARANCE_TOO_LOW | PASS | 403/CLEARANCE_TOO_LOW |
| CL-02 | TOP_SECRET 主体读 SECRET 文档放行 | PASS | 200 |
| DA-01 | 文档 ACL 生效后未名单内成员被拒 | PASS | 403/ACCESS_DENIED |
| DA-02 | 同为 VIEWER 但未授予文档 ACL 仍被拒（空间角色不足够） | PASS | 403/ACCESS_DENIED |
| DA-03 | 授予后立即放行 | PASS | 200 |
| CA-01 | 撤成员后立刻失效，不留 TTL 窗口 | PASS | 403/ACCESS_DENIED |
| CA-02 | 非成员即便有文档 ACL 也不能读 | PASS | 403/ACCESS_DENIED |
| CA-03 | 注入的过期 OWNER 决策被水位拒绝，仍按 MySQL 判定为非成员 | PASS | 403/ACCESS_DENIED acl=7 stale=6 |
| CA-04 | 缓存统计可取证：命中/未命中/过期拒绝/失效计数存在 | PASS | staleRejected=2 invalidations=11 |
| CA-05 | 过期缓存确被计入 staleRejected（不是静默回落） | PASS | 0 -> 2 |
| NG-01 | 不存在的文档 404 | PASS | 404 |
| GATE | 越权读取成功 0 例（硬门） | PASS | ALLOW rows on red resources by intruders=0 |

## 取证说明
- cache-stats before={"hits": 3, "misses": 9, "staleRejected": 0, "redisErrors": 0, "invalidations": 8} after={"hits": 3, "misses": 12, "staleRejected": 2, "redisErrors": 0, "invalidations": 11}
- 本窗口 DENY 审计条数=21；夹具对象 tenant=red workspace=a6f1e0e3-0ed6-44fe-bf28-980eb28c93f7 kb=1d611cc7-c5dc-4c5e-93a9-27a454c1c800,650ed5f9-ed33-4659-a9d0-88f8ada7cb2e doc=3072d614-9de7-4573-92e7-5ca73ea31827,fb30963d-bc3e-4299-b4a6-3eaa7fe57a9e

## 覆盖的判定层
身份映射 → 租户边界 → 空间成员/角色权限位 → 密级 → 资源状态 → 文档级 ACL → Redis 缓存水位。每层都有正向与负向用例，跨租户一律 404 且落 CROSS_TENANT_ACCESS 审计。
