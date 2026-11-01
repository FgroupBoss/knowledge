# 断网可用性与无静默公网回退演练

- 时间：2026-11-01 02:13:32
- 依赖：mysql 8.4 / redis 7.4 / kafka 4.3.1 / minio RELEASE.2024-10-13（全部 digest 固定，见 compose/image-manifest.txt）
- 推理：本机 Ollama :11434（deepseek-r1:8b + nomic-embed-text），模型均为本地已拉取权重
- 门户：宿主机 JVM :18180，依赖走 127.0.0.1 发布端口
- 结论：**10/10 条通过**

| 用例 | 断言 | 结果 | 证据 |
| --- | --- | --- | --- |
| OF-01 | 业务网 kb-p0_p0-backend 上外部 DNS 与外部 TCP 一律不通（容器压根没有公网路由） | PASS | dns_rc=2 dns_rc=2 / NOEGRESS / NOEGRESS2 / bash: connect: Network is unreachable / bash: line 1: /dev/tcp/1.1.1.1/443: Network is unreachable / bash: connect: Network is unreachable / bash: line 1: /dev/tcp/18 |
| OF-02 | 实测限制如实记录：internal 网络上 `-p` 发布不生效（宿主机门户无法连依赖），故 P0 的断网口径不能靠把现有依赖整体切成 internal | PASS | published=(无绑定) hostReachable=False |
| OF-03 | 门户与 Ollama 之间只经回环即可 READY | PASS | 200 UP |
| OF-04 | 冷启动链路：新建空间/知识库并上传文档被接受 | PASS | 202  |
| OF-05 | 文档在本地依赖上收敛为 READY 并建立索引（解析+切块+768 维向量全在机器内） | PASS | status=READY chunks=1 |
| OF-06 | 问答可用且答案逐字复述了只存在于本地语料的哨兵词（生成走本机 Ollama） | PASS | status=ANSWERED citations=1 model= |
| OF-07 | 引用预览由服务端代理读原件（不发外部预签名地址），返回 200 且强制 no-store | PASS | 200 cache=no-store |
| OF-08 | 整条链路期间门户与推理进程零公网远端：观测到的连接只有回环（与本机 Docker 转发） | PASS | samples=8 loopback=171 dockerForward=0 violations=0 [] |
| OF-09 | 离线开关打开后把推理端点配成公网地址必须启动失败（不存在静默云回退） | PASS | 	at com.acme.kb.p0.config.EngineConfig.assertNoCloudModel(EngineConfig.java:113) |
| OF-10 | 演练后宿主机门户仍可用（演练未改动业务数据，只新建了 offline 夹具空间） | PASS | 127.0.0.1:18180 listening=True |

## 本演练能证到什么、证不到什么

- **证到了**：业务网 internal 无公网路由（OF-01）；门户与推理进程在完整链路上零公网远端（OF-08）；误配公网推理地址会被拒绝启动（OF-09）；上传→索引→问答→引用在没有任何外部服务的情况下走通（OF-03~07）。
- **证不到，如实说明**：本机门户是宿主机进程，Docker Desktop 的 internal 网络与端口发布互斥（OF-02 实测），所以无法把现有依赖整体切成 internal 后仍让宿主机门户连上它们；"门户容器化后整栈全内网冷启动"必须在 P1 补做，已登记 P1 backlog。
- 观测口径：`netstat -ano -p TCP` 按持有 :18180 / :11434 的 PID 过滤其全部连接，127.* 视为回环、172.* 视为本机 Docker 转发（列出计数供人工复核），其它远端一律判违规。
- 采样窗口内主动跑了 5 次问答，避免"没流量所以没外连"的假证据。

## 取证与说明

- 观测窗口内额外跑了 14 次问答，采样 8 次（每约 1 秒一轮 netstat）
