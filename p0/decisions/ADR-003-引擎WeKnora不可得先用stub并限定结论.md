# ADR-003 候选引擎 WeKnora 在验证环境拿不到，P0 用自研 stub 引擎并把结论限定在端口层

- 状态：已采纳，**含阻断项**（2026-10-31）
- 背景：方案里 WeKnora 是首选检索引擎。本机 Docker 镜像加速站对非 `library/*` 命名空间一律 403
  （见 ADR-001），WeKnora 及其向量库依赖镜像拉不下来，也没有可用的离线安装包。
- 决定：
  1. 不在 P0 里硬凑一个"看起来接上了"的适配器。`KnowledgeEnginePort` 只暴露六个方法
     （`ensureKnowledgeBase / indexDocument / offlineDocument / deleteDocument / search / health`），
     P0 由 `StubKnowledgeEngine` 实现（索引直接落 MySQL，词法 + 768 维向量融合打分）。
  2. `P0_ENGINE_PROVIDER=weknora` 时装配 `PendingWeKnoraAdapter`，每个方法**立即抛错**并带上 ADR 编号，
     防止误配后静默用 stub 冒充 WeKnora 的结论。
  3. 所有质量与容量指标（ADR-006、`quality-baseline.md`）都标注为"stub 引擎上的上界证据"，
     换引擎必须重测；ACL 前过滤用例（`retrieval-acl.md`）设计成端口无关，可直接在真引擎上复跑。
- 后果：
  - 正面：入库/召回/引用/权限这条业务链路在 P0 就被完整验证过，引擎替换只动一个类；
    负向矩阵、状态机、幂等、离线这些结论不依赖具体引擎实现。
  - 负面（阻断项）：**"引擎选型"这一决策至今没有证据**。真引擎的分片策略、ACL 前过滤能力、
    删除语义、并发与延迟都会改变结论；`no-duplicate-index` 与 `ghost-index` 的验证强度目前只是自证。
- 责任人 / 复测：平台运维取镜像或离线包 → Java 完成适配器 → 安全复跑负向矩阵；
  目标 2026-11-06 拿到可运行镜像，2026-11-13 前重跑 `07-verify-retrieval.py`、`09-run-eval.py`。
  到期拿不到就按 No-Go 的"替代引擎/隔离层"路径重新评审。
