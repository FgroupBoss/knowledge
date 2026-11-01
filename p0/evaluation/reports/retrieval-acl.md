# 检索隔离、引用授权代理与 SSE 验证

- 生成时间：2026-11-01 01:30:56
- 目标服务：http://127.0.0.1:18180
- 结论：**29/29 条通过**

| 用例 | 断言 | 结果 | 证据 |
| --- | --- | --- | --- |
| SEED-01 | 四篇语料全部收敛为 READY（红两篇、蓝一篇、涉密一篇） | PASS | docs=4 elapsed=2467ms |
| CH-01 | 授权提问得到 ANSWERED，引用只落在本租户有权文档 | PASS | status=ANSWERED citations=2 docs={'acb0af76-5917-44bc-8900-270f456b5148', '2d100397-b897-4e58-a3f2-75b73c884971'} |
| CH-02 | 答案复述了被引用片段中的关键事实（03:15） | PASS | 结论：维护窗口安排在每周三 03:15 开始，历时 45 分钟。 [1] |
| CH-03 | 逐条复核没有把有效索引误判为过期（droppedByStaleVersion=0） | PASS | aclStats={'requestedKbCount': 1, 'authorizedKbCount': 1, 'engineCandidates': 2, 'droppedByAcl': 0, 'droppedByClearance': 0, 'droppedByStaleVersion': 0, 'promptChunks': 2, 'droppedScopes': []} |
| XT-00 | 他租户在自己的 KB 上能正常问答（证明后续负向不是整体失效） | PASS | status=ANSWERED |
| XT-01 | 他租户问红队专有事实：不得召回他租户正文 | PASS | status=ANSWERED leaks=[] |
| XT-02 | 直接指名他租户 KB：入口即 404，不进入检索 | PASS | 404/RESOURCE_NOT_FOUND |
| SC-01 | 客户端提交的越界 KB 只作提示：授权数小于请求数且越界项被记录 | PASS | requested=2 authorized=1 dropped=['1923a1d9-dfba-4014-bc66-dab811265623=RESOURCE_NOT_FOUND'] |
| SC-02 | 越界请求不得把他租户正文带回 | PASS | status=ANSWERED |
| CL-01 | 密级不足时入口即 403 CLEARANCE_TOO_LOW，不进入检索 | PASS | 403/CLEARANCE_TOO_LOW |
| CL-02 | 混合范围里涉密 KB 被剔除并计数，正文零泄露 | PASS | authorized=1 dropped=['e7bbce0e-1855-45b7-858b-cf4e9a0b4296=CLEARANCE_TOO_LOW'] |
| DA-01 | 同一 KB 内未授予文档权限的正文不得出现在检索结果里 | PASS | status=ANSWERED leaks=[] |
| DA-02 | 对照：有权主体在同 KB 内能召回被隔离的正文（证明上一条不是检索整体失效） | PASS | status=ANSWERED cited={'acb0af76-5917-44bc-8900-270f456b5148', '2d100397-b897-4e58-a3f2-75b73c884971'} |
| CP-01 | 授权引用预览 200、强制 no-store、正文按字符偏移切出 | PASS | 200 cache=no-store |
| CP-02 | 预览不含预签名 URL 或对象存储地址 | PASS | {"raw": "子系统说明：LX红1101013049 登记为 LX红1101013049。它的维 |
| CP-03 | 他租户主体取同一引用预览 404（不承认其存在） | PASS | 404 |
| CP-04 | 同租户但非该空间成员者取预览被拒且不返回正文 | PASS | 403/ACCESS_DENIED |
| CP-04b | 同空间成员预览本有权文档正常放行 | PASS | 200 |
| CP-05 | 空间成员但未被授予该文档权限者，预览文档 ACL 正文被拒 | PASS | 403/ACCESS_DENIED |
| SSE-01 | 事件顺序 meta → sources → token+ → done | PASS | content-type=text/event-stream shape=mstttttttttttttttttttttttttttttd |
| SSE-02 | 每条事件带 id(seq) 且严格递增，客户端可判断漏事件 | PASS | events=32 seq=[1]→[32] |
| SSE-03 | 流式回答不含他租户与涉密正文（本会话有权的文档 ACL 标记不算外泄） | PASS | stream chars=4149 forbidden=[] |
| SSE-04 | 无权问答在流里以拒答收尾，不出现文档 ACL 正文 | PASS | events=['meta', 'sources', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'token', 'done'] |
| GATE-01 | 本租户有权者的响应不含他租户标记 | PASS | red-admin responses=2 |
| GATE-02 | 同租户低密级/无文档权限者的响应不含涉密与文档 ACL 标记 | PASS | low responses=4 |
| GATE-03 | 他租户主体的响应不含任何红队专有标记 | PASS | blue responses=3 |
| GATE-04 | 落库引用中没有一条指向引用者所属租户之外的文档 | PASS | cross-tenant citation rows=0 |
| GATE-05 | 文档级 ACL 生效后没有一条越权引用落库 | PASS | acl-violating citation rows=0 |
| GATE-06 | 没有任何一条 Chat 记录把主体指向他租户的知识库 | PASS | cross-tenant chat_request rows=0 |

## 取证与说明
- 单请求耗时 authorization=0ms retrieval=15ms firstToken=171 total=462ms

外泄判定使用只存在于正文的随机标记词，对完整响应体扫描；同时保留有权对照用例，避免把「检索整体失效」误记成「隔离成功」。
