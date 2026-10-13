# WEB 桥接修缮与账号一次验证：1.0.1 发布记录

## 发布结论

2026-10-13 20:59 UTC+8 生产核验完成。当前 Backend、WEB、Automation、Arena Automation 均为1.0.1；生产采用候选验收过的同一批不可变镜像，未重新构建。四个当前Pod均Ready/0 restart，Argo `Synced/Healthy`。Qwen保持禁用与零副本。

- Source：`6e4e52ba65c4ce7f8535633b2c3eb8926714473e`；继承1.0.0 Source `86dad456edd1b77b04ea7340ea327ac93ef975ec`。
- [CI 38396269039](https://github.com/Prodigalgal/any2api/actions/runs/38396269039)：Backend、Automation、WEB质量门禁与镜像构建全部成功。
- GitOps：`3f06824e0dc4353ab362151c6e4daa8914c6ef0f`，只替换四个镜像tag，保留Arena资源预算与Qwen禁用配置。
- 不可变suffix：`20261013-v1.0.1-release-6e4e52ba65c4ce7f8535633b2c3eb8926714473e`。

## Changelog

| 分类 | 关键模块 | 行为变化 |
|---|---|---|
| OpenAI→WEB兼容 | WebParameterAdaptation、InferenceCoordinator、RandomInferenceRouter | 保留原生参数映射；常用可选采样、输出预算、stop、seed、penalty、logprobs没有WEB字段时省略。已有原生输出预算按已知上限及剩余上下文收敛；MiMo小输出预算不再被拒绝。普通、已选账号和随机路由统一处理，原始请求/历史/工具保留。 |
| 输入边界 | CanonicalRequestParser、ProviderRequestValidation | 已知嵌套可选null按省略处理，避免Map.copyOf引发500；未知选项和错误类型仍返回明确客户端错误。接受当前OpenAI xhigh/max档位；WEB不具备精确档位时不声称原生等效。 |
| 工具与终态 | EmulatedToolCallParser、CanonicalEventStream、ToolEmulationEngine、LongCat/MiMo解码器、OpenAiResponseWriter | 工具批次未知名称/重复ID整体验证，防止部分执行或覆盖；MiMo相同实际调用保留、禁并行生效，待解析缓冲上限1Mi字符；LongCat忽略终态后帧。上游截断在Chat返回length/content_filter，在Responses保持incomplete。 |
| MiMo数据保真修补 | MimoEventDecoder | 已识别调用区域不再被其他兜底语法重复解析；参数内JSON、调用示例及字面think标签保持数据语义。前置推理示例不产生工具调用，也不阻断普通回答；PLAIN参数仅在JSON嵌套及引号之外按逗号分隔。 |
| 账号额度 | InitialInferenceCheckRepository/Service、AccountProbeService、LifecycleScheduler、迁移034 | 每个account_id只领取一次轻量验证预算，领取提交后才调用WEB；失败、取消、重启与并发均不自动重复生成。历史526条记录迁入，重复管理验证读取历史并保留当前账号状态。保活/刷新/签到保留已有非生成流程。 |
| 模型目录与读链路 | ModelCatalogCache、ModelsController、RandomRouteCatalog、ModelRuntimeGuard、Web目录/概览 | 删除定时模型探活、可用性预测、健康聚合及健康熔断；目录查询不扫描usage_events/accounts/model_probe_results。保留已发现/启用/能力/参数、权限、真实账号资格、并发队列、普通调用账本和故障观测；缓存key为v9。 |
| 厂商故障及出口 | DeepseekEventDecoder、deepseek_api_actions、ProxyPoolService | DeepSeek HTTP200普通JSON业务拒绝正确识别：40003→credential_rejected、禁言→account_blocked，未知拒绝不再误报空响应；绑定upsert更新traffic_scopes，七家注册/推理/生命周期均经Self-hosted Oracle池。 |

OpenAI核心Chat/Responses接口与工具身份/状态资源契约保持。原健康扩展字段被删除，1.0.0按major交付并同步控制台；依赖这些旧扩展的管理客户端需调整。1.0.1为工具数据边界补丁，无新数据结构；迁移034仍归属1.0.0。

## 验证证据与边界

- 最终本地Backend 581项：576通过、5按环境条件跳过；bootJar与源码/JAR/迁移版本校验通过。修补相关39项通过；旧失败样例已保存。
- Automation 563项、Ruff及WEB lint/build门禁通过；最终冻结Source的三项CI质量门禁全部通过。
- 官方OpenAI Python 2.54.0本地夹具26/26：工具往返、strict、namespace/custom身份、Chat/Responses流式、状态资源/跨Key隔离、错误终态，以及常用参数/流式四组合。七家两协议参数组合回归通过。夹具使用模拟上游，不能作为七家真实WEB生成验收。
- 64Ki工具JSON/4100分片本地测量：auto每次分配135,165,376→559,186 bytes，required 135,181,488→548,469 bytes，下降约99.6%；对应均值17.68→3.46ms、14.40→2.95ms。此结果仅代表桥接缓冲成本，不能代表公网Read延迟或厂商首字延迟。
- 1.0.1候选与生产版本/镜像/安装检查通过，四个digest完全一致；两组自有候选各7个临时资源已清理。
- 七家候选目录均移除健康预测，参数适配模式符合声明；526个账号全部具有HISTORICAL记录，缺失0/INITIAL0。候选每家重复两次共14次，生产MiMo重复两次，均performed=false、状态保留、无新增生成；原模型探活接口404。
- 两个公网入口`https://any2api-direct.mnnu.eu.org/v1`与`https://any2api.mnnu.eu.org/v1`的health/ready、MiMo目录、匿名Responses 401/OpenAI JSON契约通过。Actuator信息匿名401、管理登录后200；业务版本通过实际镜像、启动日志与安装包核验。
- Self-hosted Oracle当前为4节点、revision473，七家均绑定INFERENCE/LIFECYCLE/REGISTRATION；20:58 UTC+8四节点只读gstatic检查均204。未修改节点源或分发密钥。
- 本轮主动验证未发送厂商生成请求；因此未重跑七家真实WEB生成矩阵，不能将只读目录或代理204宣称为模型可用。

## 当前不可变运行制品

| 组件 | 当前Pod | Docker digest（候选与生产相同） |
|---|---|---|
| Backend | any2api-server-cd748b457-4zg72 | sha256:98bf0050b1e5c7a38b55d7b64d82e66e58fcfdd83f8ec3463537ea3f85744164 |
| Automation | any2api-automation-7dc8668d9f-8ddk8 | sha256:6970e32cd9adfae5de49c1fbe851daec2210ffdb4fd8859cf7a6482521ded519 |
| Arena Automation | any2api-automation-arena-5d7c6756f5-9vmr5 | sha256:95e1976bd61ff32e37a9537140016151f46eb45a36c991a12be7ee21c84fc486 |
| WEB | any2api-web-d64696f75-c7plq | sha256:4eb2585fa5433a572c3a637259049050fbce695e7808d3422d85771c676c34a0 |

## 遗留与回滚

1. Arena历史真实调用超时尚未完成恢复验收；本轮改为只读验收，不推断已恢复或仍持续超时。
2. DeepSeek仍无启用且ACTIVE/DEGRADED的账号，至少一个历史账号存在禁言证据；解码修复不等于账号恢复。
3. MiMo桌面“你好”此前实际包含约69K字符system与31个大工具定义。WEB上下文能力有限；本轮保留这些关键内容，未做上下文压缩，不能声称支持客户端配置的1M上下文/128K精确输出。
4. 服务Ready/零重启是本次验收窗口证据，不是长期稳定性结论；端到端Read p95及上游首字延迟仍需分别测量。

建议回滚到已验证1.0.0四镜像：`20261013-v1.0.0-release-86dad456edd1b77b04ea7340ea327ac93ef975ec`，保留首次验证表与历史预算，不做数据库破坏性回滚。回滚到0.30.1会恢复旧周期模型探活及健康预测，应优先避免。
