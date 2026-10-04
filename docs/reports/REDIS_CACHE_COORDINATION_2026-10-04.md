# 0.26.3 Redis 缓存等待、协调错误与 Read 复测

## 范围与当前交付状态

本轮承接统一 OpenAI Chat/Responses 客户端契约、厂商 WEB 桥接及 Read 性能问题。范围见 [执行规格](../requirements/REDIS_CACHE_LATENCY_FOLLOWUP.md)，客户端契约见 [API_CONTRACTS](../architecture/API_CONTRACTS.md)。Qwen 的账号和真实推理继续排除。

- 源码：`2388687ea3675e14a24dead88bb91f37b4663ceb`，业务版本 `0.26.3`，已推送 `main`。
- [CI 37165657001](https://github.com/Prodigalgal/any2api/actions/runs/37165657001) success：远端 Backend、Automation、Web 门禁通过，四个 ARM 不可变镜像发布。GitOps 提交 `b66f59ab7ea384c5220c29d3e315c111bce69a94`，镜像后缀 `20261004-v0.26.3-release-2388687ea3675e14a24dead88bb91f37b4663ceb`。
- Argo `Synced/Healthy`；Server `any2api-server-77d55bd958-7zp99`、Web `any2api-web-64c88cd7d8-7nb4x`、Automation `any2api-automation-5d4c775dbb-blv8l`、Arena Automation `any2api-automation-arena-5ddb4cb475-5xh2l` 均 1/1 Ready、restart=0。两个 Automation project/installed/API `0.26.3` PASS；完整镜像 digest 保留在运行证据中。
- 本地 Backend 487 tests：482 passed / 5 条件 skipped、0 failures/errors；`bootJar` 通过。Automation 508 passed，ruff format/check 通过；Web lint/build 通过。七个版本声明与 JAR 均为 0.26.3，无新增数据库迁移。
- 官方 OpenAI SDK 2.54.0 经真实 HTTP、生产 SecurityConfiguration 和隔离 PostgreSQL fixture：common 12 组、strict 8 组全部通过。fixture 已关闭。未对生产故障注入。

## Changelog

| 分类 | 关键模块 | 行为与兼容性 |
|---|---|---|
| 缓存性能 | `LayeredJsonCache`、`ApiKeyAuthenticator`、`ModelCatalogCache`、`PromptExactCacheManager` | 每次 Redis 读/写/失效默认预算 250ms；慢读按已有数据库/空缓存规则回源，保留 L1/L2 TTL、single-flight、权限和一次异步写重试。不会把空数据库结果伪造成授权或缓存命中。 |
| 运维配置 | `Any2ApiProperties`、`application.yml` | 新增 `ANY2API_CACHE_REDIS_ACCESS_TIMEOUT`，默认 `250ms`，拒绝 null/零/负值；全局 Redis 3s 和关键账号租约预算不变。250ms 是单次缓存访问预算，不是 HTTP 总耗时上限。 |
| OpenAI 错误 | `AccountLeaseService`、`CoordinationUnavailableException`、`OpenAiResponseWriter`、`ApiExceptionHandler` | 获取/续租资源超时或连接失败归一为 `coordination_unavailable` / `retryable:true`；首 SSE 前 JSON 503，已提交 Responses SSE `response.failed` / Chat error + `[DONE]`。安全消息不包含底层 endpoint 或原始异常。支持异步清理包装的 cause 链。 |
| 关键租约 | `InferenceCoordinator`、`ModelRuntimeGuard` | 续租协调异常不误入厂商分类/凭据处置或模型熔断；获取失败不授予容量，fencing、owner、Lua、容量和 TTL 保留。生成收尾释放的协调失败记录请求/账号上下文并依赖原 TTL，不生成冲突终态；释放服务本身仍失败，不伪造释放成功。 |
| 可观测性 | `InferenceTelemetryService` | 错误账本使用稳定的 `coordination_unavailable`，保留真实失败与请求证据；请求级统计仍记录失败，不宣称 Redis 故障消失。 |
| 回归 | Cache/Lease/Writer/Coordinator/RuntimeGuard/API/Properties tests | 覆盖 Redis 永不返回、并发单次回源、数据库错误、空结果、有界写入/失效、四种租约操作的资源故障、容量/正常 fencing、续租分类、收尾终态和两协议错误。 |

此前 0.26.2 的 strict missing/null 参数修复、模型详情、工具/媒体历史、权限和默认 `store:false` 保留。本次不创建/更换分发 Key，不迁移 Redis/PV，不改变厂商生成实现。

## 生产厂商与真实 Codex 验证

- 0.26.2 的 Arena 今日有限复测 4/4 PASS，包含 Chat omitted 与 Responses null strict 空参数；昨日两次凭据失败独立保留在 [上一轮报告](OPENAI_CLIENT_CONTRACT_2026-10-03.md)，本次成功不证明凭据波动根治。
- 0.26.3 官方 SDK 2.54.0、既有七家分发 Key、direct 统一入口，最多两个并行客户端；七家所选模型每家 4/4、共 **28/28 PASS**。四项包括模型详情/strict 能力、缺失模型 JSON 404、非法 strict schema 400、Chat omitted + Responses null 无参数 strict 调用，两次真实推理均只返回 `{}`。SDK `max_retries=0`，本轮没有重复执行客户端矩阵；后台透明重试另行核对。

| 厂商 | 实测模型 | 检查 | 两次无参数工具生成合计 (s) |
|---|---|---|---:|
| DeepSeek | `deepseek/default` | 4/4 PASS | 71.115 |
| LongCat | `longcat/longcat-flash` | 4/4 PASS | 10.083 |
| Grok Web | `grok_web/grok-3` | 4/4 PASS | 26.810 |
| Arena | `arena/Max` | 4/4 PASS | 37.235 |
| GLM | `glm/glm-5.2` | 4/4 PASS | 75.667 |
| MiMo | `mimo/mimo-v2.6-flash` | 4/4 PASS | 29.024 |
| MiniMax | `minmax/MiniMax-M3.1-Flash-Preview` | 4/4 PASS | 15.596 |

这是本次变更的差量验证，不等于重跑上一版所有图片、函数结果续接、全部模型或容量矩阵；完整旧版桥接结果保留在对应报告中。差量后七个模型 guard 均 concurrent=0、queue_depth=0、circuit=CLOSED。Codex 收尾后的快照中 MiMo 等六个模型 concurrent=0，DeepSeek concurrent=1；日志确认该时段有后台 `model-probe-67aab9d4-731d-4669-a3f2-00b05f12934a`，随后 09:07:23（UTC+8）完成 READY。该背景工作不列为测试资源泄漏，也不把快照改写成全零；七个队列均 0、熔断 CLOSED，四组件仍 Ready/restart=0、Argo Synced/Healthy。

真实 Codex CLI 0.160.0、现有 MiMo Key、`mimo/mimo-v2.6-flash`、只读 sandbox、关闭 apps/plugins/multi_agent，使用既有 640×480 合成图片。两次 `POST /v1/responses` 均 HTTP 200，且每次只有 `response.completed` 成功终态；没有 HTTP/SSE 失败或客户端重试。第一请求生成 `view_image`，第二请求确实包含 `function_call_output(input_image)`，最终正确读出 `6248`。请求分别为 `946303ce-3842-4dc5-9cb3-71939a68adc7`、`ef0db96c-8937-45b0-966a-6727745fd3a7`，后台均 attempt=1 成功。临时 relay 和工作目录已清理。

CLI 仍有 model metadata fallback 提示，原文保留在受控证据；本轮 CLI 的七个 function definitions 没有显式 strict。此项证明该 MiMo 模型的常用真实 agent 图片闭环，不替代显式 strict、其他厂商或 Shell/patch 执行权限验收。

数据库只读补查测试 Key 的 INFERENCE 时窗（DB 时间 00:53:13 UTC 起，含七家两次调用及 Codex 两次调用）：**16 个逻辑请求，17 次后台尝试，最终 16/16 成功**。其中 MiMo Chat 请求 `83574c9a-2b72-4a36-b4cc-c69225c31b4e` 的 attempt 1 发生 `tool_call_generation_failed`、output_tokens=0，11,499ms；换不同账号 attempt 2 成功，4430ms。中间失败未隐藏，不能说 17/17 首次成功。全部 queue_ms=0，account_acquire_ms=99–140ms；本次测试时窗未复现 3s 协调超时，不代表历史根因已消除。

## 部署前 Read 基线与网络证据

以下为 0.26.2 的 2026-10-04 时点样本。相同六条 GET 每条 n=5，全部 200；cluster 从现有 Automation Pod 发起，direct 从本机经 `any2api-direct.mnnu.eu.org` 发起。两者不是同一网络路径，n=5 不代表生产 p95 或容量压测。未打印密钥、管理员密码或响应正文。

| GET 路径 | cluster p50 (ms) | direct p50 (ms) |
|---|---:|---:|
| `/healthz` | 79.97 | 440.85 |
| `/api/admin/v1/overview` | 83.05 | 437.06 |
| `/api/admin/v1/accounts/page?page=0&size=25` | 86.41 | 446.84 |
| `/api/admin/v1/api-keys` | 83.24 | 440.35 |
| `/mimo/v1/models` | 91.04 | 456.56 |
| `/v1/models` | 147.73 | 1028.28 |

完整目录响应约 1,533,101 bytes，压缩后约 109,967 bytes；保留全部字段。direct Key list 曾有 929.43ms 样本、完整目录 max 1806.27ms。Hikari pending=0，Lettuce 累计平均约 135ms、近期 max 164.51ms；这些时点数据不覆盖昨天故障窗口。

只读原始 socket 对比（每条单连接五次 PING，未认证，只计 Redis 响应头往返，不写 Redis）：

- Server Pod → Redis Service：p50 **77.155ms**，max 77.286ms；Server 与 Redis 位于两个不同节点。
- Redis 同节点 Automation Pod → 同一 Service：p50 **0.130ms**，max 0.251ms。
- Server 本机 `/healthz` HTTP 首行：p50 **2.640ms**；对照跨节点 cluster health 79.97ms。

当前证据确认跨节点链路有明显基础往返成本；结合 direct health 和大目录，网络/传输占据正常 Read 耗时的一部分。它没有证明历史 3s Redis 超时的唯一根因。缓存预算改善慢缓存尾部等待，不能宣称所有 Read 的正常延迟都已解决。

部署后的第一窗口（同样两条路径、每 GET n=5，全 200）：

| GET 路径 | cluster 0.26.2 → 0.26.3 p50 (ms) | direct 0.26.2 → 0.26.3 p50 (ms) |
|---|---:|---:|
| `/healthz` | 79.97 → 90.73 | 440.85 → 477.18 |
| overview | 83.05 → 99.30 | 437.06 → 325.77 |
| accounts/page | 86.41 → 119.77 | 446.84 → 654.85 |
| api-keys | 83.24 → 92.82 | 440.35 → 405.08 |
| MiMo models | 91.04 → 98.60 | 456.56 → 427.02 |
| all models | 147.73 → 177.04 | 1028.28 → 631.95 |

模型目录 direct 样本下降，但 cluster 和 accounts 样本变慢；不能据此宣称普通 Read 普遍改善。旧基线为运行约 11h 的实例，新窗口为启动后初段，首个目录/overview 等有冷启动样本，WAN 波动也明显。部署后 Hikari pending=0；Lettuce 近期 max 276.40ms，模型目录出现一次缓存 `TimeoutException` 后仍回源成功，全量 Read 均 200。原始首轮结果保留，后续稳定窗口用于补查这一具体波动。

启动稳定后的补查（仍为每条 GET n=5，两条网络路径共 60/60 200，原始首轮不覆盖）：

| GET 路径 | cluster 稳定窗口 p50 (ms) | direct 稳定窗口 p50 (ms) |
|---|---:|---:|
| `/healthz` | 82.58 | 436.34 |
| overview | 87.35 | 354.75 |
| accounts/page | 95.91 | 774.01 |
| api-keys | 92.53 | 628.94 |
| MiMo models | 94.05 | 566.33 |
| all models | 159.50 | 1587.26 |

cluster 普通 Read 已接近原基线，但 accounts 95.91ms 对旧 86.41ms、Key 92.53ms 对旧 83.24ms、完整目录 159.50ms 对旧 147.73ms，仍未出现普遍改善。direct 波动进一步增大，health max 1899.83ms、accounts max 1770.40ms、完整目录 max 2038.05ms；这不能全部归因于数据库或 Redis。Hikari pending=0、Lettuce 近期 max 113.92ms。结论是本轮解决缓存慢依赖的有界等待/错误语义；公网正常性能仍有明显待优化项，不继续重测来挑选更好的数值。

## 遗留风险与回滚

- 历史 Redis 3s 超时仍需故障窗口指标/网络证据；本轮不迁移持久化依赖，也不将关键租约改为失败放行。
- 缓存超时后会增加数据库回源；缓存失效保持原有 best-effort/TTL 边界，释放失败可能暂时占用容量至租约到期。`250ms` 可配置，需结合真实分位数判断。
- 全量目录/WAN 传输仍需优化；Arena 凭据波动、LongCat 纯色识别、Cloudflare 自身 502/524 正文未解决。Worker 尚未发布/绑定，Qwen 不处理。
- hosted tools、WebSocket、background、Conversations、compact、自定义 grammar 等仍在常用桥接范围外。
- 回滚点：0.26.2 源码 `3c925237abb68de50d3af99d30a6e9f80c9cb67d`、GitOps `ddb7e6824ba5aa14cae9c61c2f2d7c1d049c5575` 对应四个不可变镜像；无数据库迁移、Key 恢复或 PV 迁移步骤。不要复用/覆盖已发布 0.26.3 镜像。

## 原始证据索引

本地 `backend/build/` 保留：`redis-v0263-local-summary.json`、全量/专项门禁 logs、`common-v0263-fixture.json`、`strict-v0263-fixture.json`、`redis-v0263-reads-{before,after,stable}-{in-cluster,direct}.json`、两条 `*-socket-rtt.json`、`redis-v0263-server-local-health.json`、`strict-v0263-suite.json`、逐厂商 logs/JSON、`release-0.26.3-{runtime,final-runtime,post-codex-runtime}.json`、`codex-v0263-image-live.json`、`redis-v0263-live-{window,attempts}.json`。部署后证据另存新文件，旧版/失败样本不覆盖。
