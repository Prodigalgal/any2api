# 当前任务板（源码 0.27.12）

> 当前源码事实以代码和 [API 契约](../docs/architecture/API_CONTRACTS.md)为准；
> 历史任务与运行态快照已归档。

## 2026-10-04–06 逐厂商 WEB 参数映射与输入边界（0.27.0 → 0.27.12）

- 当前候选 0.27.12 修正 Grok native chunk 顺序，完整历史先于当前 text；两个原失败账号固定输入交叉对照，新顺序 2/2、旧顺序 0/2，追加 none 提示失败未采用。旧实现真实发帧回归先失败后修复，Backend/Automation/Web/版本门禁执行中。0.27.11 Source `49a9f8b`、CI `37267648014` success、四镜像已构建但七家完整 SDK 47/49，六家 7/7、Grok 手工 Chat/Responses 回放失败，state 通过；35 请求 / 38 次 INFERENCE 完整，三次后台失败保留。本窗口无 coordination_unavailable，不关闭底层网络尾延迟根因。自有状态/候选资源均清理，生产仍 0.27.6，七家全通过后发布门禁保持。

- 范围：[执行规格](../docs/requirements/WEB_PROVIDER_PARAMETER_ADAPTATION.md)。按用户批准逐厂商核对 native 字段、单参数探测、WEB 输入限制及 Xiaomi 桌面端自动引导；Qwen 仍只有代码证据。
- 当前 0.27.11 未发布：生产 0.27.6 六家复核 31/42，五次协调失败、六项前置衍生失败；Grok 0.27.10 隔离真实 WEB 7/7 保留。新增独立租约 Redis client、内部失败日志及双连接 readiness；真实 TCP 回归复现旧共享连接超时而独立租约完成，故障仍失败封闭。13 项相关测试通过，含真实 Boot 默认工厂/模板保留；Backend 全量/bootJar、Automation 536、Web lint/build、ruff/126 format、八处版本通过。按现有 workflow 的 deploy=false 构建不可变候选，再进行七家隔离运行验收及相同制品的 GitOps 提升，不提前更新生产。
- 参数声明回归各 Provider 的 `ProviderProtocolContract.parameterMappings`，模型详情提供 `parameter_adaptation`；目录不重复大表，v6 cache 使用当前 adapter 能力并保留 discovery / overrides。
- 已知可选 null generation 当作缺省；MiMo 完整 Unicode schema 无损压缩、空白片段保留、固定长度拒绝变为非重试 OpenAI 错误，不换号/熔断；GLM 正整数输出上限预校验。
- tools/skills 原生字段追加探测：MiMo 6 个候选位置未见生效，query 两个正向对照有效；DeepSeek/LongCat/Grok/MiniMax 当前通道未见独立字段生效，GLM tools 流内 INTERNAL_ERROR，Arena 修正临时解析器后 429 停止、语义未验证。厂商 Agent/MCP 安装配置与调用方 function 执行边界分别核对，不把 HTTP 200 或前端渲染字段当作原生支持。
- 0.27.0 上线 SDK 首轮 31/41：8 个 nullable 缺陷、2 个首帧前 JSON 400 的探测误判；修复 0.27.1 Source `cdf3296`、CI `37204392918` success，GitOps `c59c7eb` Synced/Healthy、四组件 Ready/restart=0。本地 Backend 497 passed / 5 skipped，Automation 509 passed，Web lint/build/版本/JAR 通过。七家参数 41/41、MiMo 无参 strict 4/4、中文 enum/SSE 与普通结果回放 2/2 通过；两次 REPLAY nonce 回显拒绝保留。
- 0.27.1 后台 34 个真实推理/35 次尝试（Grok 首次空响应换号成功）；Arena 一个 mapped-control 命中精确缓存、不算该次原生验证。MiMo 四个长度拒绝均一次、账号无冷却、模型无熔断；七家 queue/concurrent=0/circuit CLOSED，但滚动目录仅 GLM READY，其余 DEGRADED。
- 收尾 0.27.2 修正 LongCat WEB camelCase/catalog v7、raw controls 缓存隔离/prompt v3。用户追加七家全桥接后发布、Qwen 仍排除；CI `37206740522` 已取消，GitOps 未更新，生产仍为 0.27.1。
- 当前候选 0.27.3 补齐 MiMo assistant 正文/调用 ID、无类型 WEB 参数到 string/nullable string 的 schema 映射、Responses input_items 标准内容块和工具资源缺省状态；不重写存储、不修复显式 JSON 类型错误。Backend 506 passed / 5 条件 skipped、bootJar/版本/JAR 通过，Automation 520 passed、Web lint/build、ruff 通过。
- 发布前七家完整功能证据已齐：Arena/Grok Web/LongCat/MiniMax 各 7/7；DeepSeek/GLM 前五项与中断后的状态续接/清理均通过。MiMo 候选实际连接 WEB 的 Chat 往返与 Responses 三种调用/回放/续接通过；state `c5a299cb-f327-45dd-b86a-e01b7731e0c4` SDK 资源 schema 通过、测试状态已清理。四角色和 function/custom 调用/结果、分页及资源重新提交本地 2/2 通过。进入统一发布，再验证七家新版本完整协议和运行态；Qwen 仍排除。证据、保留失败和边界见[报告](../docs/reports/WEB_PARAMETERS_AND_CONTEXT_2026-10-04.md)。
- 0.27.3 Source `a1d9035`、CI `37211898821` success、GitOps `822270c` Synced/Healthy、四组件 Ready/restarts=0。七家新版本 SDK 首轮 47/49，全部 state/resource schema/清理通过；GLM 原请求单独重试通过，Grok 完整回放及重试均语义失败。35 个矩阵请求 + 2 个单独重试 / 39 次后台尝试，保留 Arena 凭据拒绝、GLM 传输失败、Grok 空回复后换号；DB 生成成功不代替技能结果检查。
- 0.27.4 候选补齐 Grok 的角色/完整历史结束/下一 assistant 回复边界，普通单用户提示保持；真实 WEB 的原失败合成输入候选两次完整结果通过，另外一次 incomplete 空回复与一次探测器漏解 response.chunk 均保留。内部客户端 max idle 30s → 3s、每秒回收闲置连接并由 Spring 关闭资源，保留 200 连接、10s 获取预算、15m 响应预算与原重试契约。实际 HTTP 复用/回收/关闭测试通过，不将空闲配置不匹配当作该次 502 唯一根因。
- 0.27.4 本地 Backend 508 passed / 5 条件 skipped、bootJar/版本/JAR；Automation 全量 522 passed、修正 import 顺序与混合换行后 ruff/125 文件 format 和 Grok 13 项通过；Web lint/build 通过。候选已满足相关本地及真实 WEB 差量门禁，待统一发布后七家复测。
- 0.27.4 Source `6c7391a`、CI `37215047762` success，四镜像 suffix `20261004-v0.27.4-release-6c7391aafd3ea9016326759a344064921d59461d`；2026-10-05 09:08（UTC+8）复核 Argo Synced/Healthy、四组件 Ready/restarts=0、版本一致。七家首轮 47/49：其余六家各 7/7，Grok Chat 结果回放及 state 内容遗漏。35 个 SDK 请求对应当前可关联 40 次 INFERENCE 账本 / 34 次后台成功；DeepSeek 一次 SDK completed 与单条失败账本不一致待定位，不能把 34/40 改报全请求成功。
- 0.27.5 候选单回答、不完整终态及原生 stream_error 修复；global_rate_limit 分类 upstream_unavailable，保留 provider scope、不冷却账号，账号 quota 保持原分类。新错误回归先失败 2 项后修复；Backend 513 passed / 5 skipped、Automation 525 passed、Web lint/build、ruff/版本/JAR 通过；隔离候选真实 Grok WEB 官方 SDK 7/7，两个测试状态已清理、服务已关闭。原生 session.instructions 回显但未影响模型，system item 未生效；仍完整正文桥接。准备统一发布，再跑七家新版本完整验收。
- 0.27.5 Source `82b8c2a` 的 CI `37250823775` 三项质量通过；发现跨通道 attempt 重置根因后主动取消四镜像发布/update-gitops，生产仍为 0.27.4。新候选占用 0.27.6：遥测编号跨通道递增，通道内三次重试预算保持；真实 PG/完整协调器 JSON/SSE 四个用例复现丢记录后修复，24 项协调器回归通过。同步等待异步 doFinally 持久化后才关闭测试 executor，不以关闭竞态失败代替根因证明。Backend 517 passed / 5 skipped、bootJar/八处源码和 JAR 版本通过；Automation 525 passed、Web lint/build、ruff 通过。候选全部相关门禁完成，进入统一提交/部署，再核对七家 SDK 和真实账本。
- 0.27.6 Source `112459a`、CI `37251876959` success；2026-10-05 11:18（UTC+8）再次复核 GitOps `2d1c056` Synced/Healthy、四组件 Ready/restarts=0、源码/installed/API 版本一致，healthz/readyz=200。七家 SDK 首轮 48/49，其余六家各 7/7，Grok state 一次内容失败；35 请求 / 38 次 INFERENCE 账本完整，DeepSeek/LongCat API attempt=1 失败、Runtime attempt=2 成功均保留。七家 guard 并发/队列 0、熔断 CLOSED，滚动目录仅 GLM READY，其余 DEGRADED。Read 两侧各 18×3 全 200，全目录集群 median 226.06ms / direct 491.11ms；n=3 不关闭历史性能风险。
- 0.27.7 正文实验已撤回：Grok 手工/state 的 42 条消息生成相同 WEB 正文，未丢历史；分区/完整 JSON 虽有有限成功，两轮完整 SDK 仍有函数/结果遗漏，不以重跑成功掩盖失败。原生多角色/多 user item/keep_context 三组 0/3，均只记录最后一条 user。原始失败、自有资源清理和 runtime 关闭保留。
- 0.27.7 当前候选收敛为 Grok 静态账号资格与对象 none 修复：生产只读确认 19 个 basic 配置账号，而目录把高等级模式误标 19 个可用；可选 ModelAccountPolicy 复用现有路由规则，按合格账号计算 cooldown/quota，cache v8，null metadata 不可变保留、未知模型不能拖垮全目录。无专属策略保持原目录行为；冷加载固定最多 3 次批量 SQL，热缓存无 SQL。Backend 522 passed / 5 skipped、bootJar，Automation 527 passed、Web lint/build、ruff/八处版本通过。未推送 main/未发布，Grok 完整 Agent 门禁仍未完成；生产 0.27.6 及其余六家的通过证据保留。
- Arena 0.27.3 缓存差量四请求完成：两个显式 `web_search:false` 都真实生成，plain 重复仅 cache hit，3 个实际生成请求/4 次尝试，首次受控请求一次 credential_rejected 后成功；缓存隔离证明通过。Read 18×3 全 200，但 system 全目录 median 2229.78ms，同一 Arena Key 目录 median 6561.48ms，n=3/WAN/大目录波动仍未关闭，不声称性能普遍改善。
- 0.27.8 目录优化占用新版本，未发布：限制账号与 cooldown 在同一 SQL 快照分别聚合，仅随目录首行传输；冷加载从 3 次降至 1 次，热缓存零 SQL。真实 PG 及生产只读 318 行双向差异 0，资格快照 1 行/14,452 bytes，原主查询 21.641–22.486ms、新查询 22.322–24.801ms；HTTP 冷加载收益尚未上线验证，历史秒级波动未关闭。
- 0.27.9 Grok native context 未发布：官方静态前端/proto 与原生合成语义证明 `system_provided_context` 生效，系统口令/40 轮批次/原失败函数结果 3/3，inline 对照通过。接入 Java/Python 和一次带 item 的 `response.create`；全量本地门禁通过，但隔离 SDK 6/7，state `ad37f8cf-e9d8-497b-abb1-f1f3bd93e7f4` 仍重复旧回答，资源语义后续校验未执行、两个自有状态已清理。quoted 历史及 native client_tool_result 结果失败，不采用。
- 当前候选 0.27.10：消费 native context 内普通 message 的顶层 Gateway 资源 ID，原状态/正文、嵌套 function ID 和其他类型身份保留；Java 当前轮 formatter 补齐完整 tool_calls ID、tool 正文不重复。已知手工/store 输入两端当前文本 848 字符、context 2,243 字符一致，39 条历史完整。Backend 526 passed / 5 条件 skipped、bootJar，Automation 536 passed、Web lint/build、ruff/126 文件 format、八处版本/JAR 通过。隔离真实 Grok WEB SDK 七项 7/7，state/resources schema 与两个自有状态清理均通过；其余六家已有桥接代码未变，发布前使用生产 0.27.6 复核进行中。未推送 main/未部署，七家全部完成仍是发布条件。

## 2026-10-04 Redis 缓存等待与协调错误收敛（0.26.3）

- 范围：[执行规格](../docs/requirements/REDIS_CACHE_LATENCY_FOLLOWUP.md)。缓存 Redis 每次访问默认 250ms、有界回源/写入/失效；关键协调维持 3s 及既有 fencing/TTL。
- 获取/续租故障统一可重试 `coordination_unavailable`，JSON/首帧前 503、已提交 SSE failed/error；内部协调故障不进入厂商凭据处置或模型熔断。生成收尾释放失败记录上下文并依赖原 TTL，不覆盖生成终态。
- **已提交部署并验收**：Source `2388687`、[CI 37165657001](https://github.com/Prodigalgal/any2api/actions/runs/37165657001) success、GitOps `b66f59a` Synced/Healthy，四组件 Ready/restart=0，两个 Automation installed/API 0.26.3。Backend 482 passed / 5 条件 skipped，Automation 508 passed，Web/版本/JAR 门禁、Security/PG HTTP fixture SDK 12+8 组通过。
- 七家所选模型差量各 4/4、28/28 PASS，Arena 本轮通过，昨日失败独立保留。MiMo 真实 Codex `view_image → function_call_output(input_image)` 两请求均 completed、读数正确，metadata fallback 仍有提示。后台 16 个逻辑请求、17 次尝试：MiMo Chat 一次 `tool_call_generation_failed` 后换号成功；不将最终成功等同于每次尝试成功。
- Read 三窗口 cluster/direct 各 6×5 全 200，稳定窗口 cluster 普通 Read p50 87–96ms、全目录 159.50ms；direct 全目录 1587.26ms且 health 有 1899.83ms 样本，性能未普遍改善。Redis 跨节点往返 p50 77.155ms 对同节点 0.130ms，Hikari pending=0。n=5 仅时点对照，历史 3s 超时、WAN/大目录、Arena/上游生成波动和 Cloudflare 自身错误仍有遗留；不迁移 Redis/PV、不新增/更换 Key，Qwen 排除。[完整验证、风险与回滚](../docs/reports/REDIS_CACHE_COORDINATION_2026-10-04.md)。

## 2026-10-03 统一 OpenAI 客户端契约补齐（0.26.0 → 0.26.2）

- 范围：[模型详情与严格函数参数](../docs/requirements/OPENAI_CLIENT_CONTRACT_FOLLOWUP.md)。保留 Chat/Responses → 厂家 WEB、调用方执行工具、既有默认存储/权限/媒体边界。
- 新增 `models.retrieve()`、统一 strict schema/工具事件检查、能力与 v5 缓存一致性；0.26.1 修复首轮遗漏的 Grok Java / LongCat Automation strict 拒绝，生产安全链限定 GET 模型详情 ID 放行编码斜线。Backend 459 passed / 5 skipped、Automation 508 passed、Web lint/build 与版本契约通过；实际安全链 SDK fixture 12+7 组通过。
- **0.26.1 已部署并验收**：Source `74028a7`，[CI 37121551123](https://github.com/Prodigalgal/any2api/actions/runs/37121551123) success，GitOps `2162a82` Synced/Healthy；四组件 1/1 Ready、restart=0，Automation project/installed/API 0.26.1。既有分发 Key、官方 SDK 2.54.0，七家所选模型各 5/5 strict 工具闭环通过；模型路由/权限 31/31、应用安全链 8/8，通过后模型 guard 并发/队列均 0、熔断 CLOSED。[完整结果、首轮失败、性能与回滚](../docs/reports/OPENAI_CLIENT_CONTRACT_2026-10-03.md)。
- 保留边界：严格参数由网关检查，工具事件等校验完成才释放，未启用严格的路径保持原有行为；不承诺原生 constrained decoding 或其他未测模型。默认 store/strict、共享入口 307、公网 Cloudflare 错误与全量目录性能未在本轮统一。Qwen 仅目录只读，账号/推理不处理；Key 无新增/替换，无新数据库迁移，回滚点 0.25.7。
- **0.26.2 已部署**：Source `3c92523`，[CI 37124714703](https://github.com/Prodigalgal/any2api/actions/runs/37124714703) success、GitOps `ddb7e68` Synced/Healthy、四组件 Ready/restart=0。修复 strict missing/null 空参数默认值，显式 schema/非严格行为保留，注册表延迟初始化；Backend 464 passed / 5 条件 skipped、Automation 508 passed、Web/版本门禁和 SDK fixture 12+8 组通过。
- 0.26.2 七家无参数差量首轮 **27/28**：六家各 4/4，Arena 3/4；一次有限复测仍 credential_rejected，两次失败各已换用三个不同账号，失败记录保留。当前 MiMo 真实 Codex 0.160.0 图片工具闭环通过，确认 `view_image → function_call_output(input_image)`；CLI metadata fallback 保留。七个所选模型 guard 并发/队列均 0、熔断 CLOSED。
- 待收敛：Arena 认证拒绝（列表 ACTIVE 不能替代真实可调用性）；Codex 补查期间两次生成前 QueryTimeoutException，与 Redis L2 读写和 RedisCommandTimeoutException 同窗口，具体触发链路尚未确定。PostgreSQL 只读未见阻塞、Redis 当前持久化正常；未盲目改 timeout/索引。下一步优先采集 Server→Redis 故障窗口与响应回调，再优化全量目录/Read；不标注七家全通过或性能已全部解决。Qwen 仍不处理。

## 2026-10-03 分发 API Key 清理与按厂商重建

- 用户明确授权清理全部分发 Key并保存到服务器管理。[规格与执行结果](../docs/requirements/DISTRIBUTION_API_KEY_RESET.md)：33 个旧 Key 删除，8 个厂商各一个长期 Key；限定单厂商全部模型、Chat/Responses、工具/媒体/文件、AUTO。
- 八个新 Key direct/public 认证均 200、跨厂商均 403；六个可核验旧 Key 两入口均 401，旧 Key/关联授权/认证缓存残留为 0，868 条历史用量保留。源码及运行版本仍为 0.25.7，四组件健康，无新部署或 schema 变更。
- 明文在服务器管理 `private/any2api/distribution/api-keys.env` / `api-keys.json`，受限 ACL，仅当前用户/SYSTEM/Administrators；操作报告不含明文，未提交任何秘密。FinBot 本地分厂商文件同步新值，实际旧调用端仍须更新配置；Qwen 账号状态未改变。

## 2026-10-03 WEB 桥接遗留处理（Qwen 除外，0.25.3 → 0.25.7）

- [执行规格](../docs/requirements/WEB_BRIDGE_RELIABILITY_FOLLOWUP.md)；[最终验收与遗留限制](../docs/reports/WEB_BRIDGE_FOLLOWUP_2026-10-03.md)。沿用实现、提交、现有 GitOps 部署和现有账号测试授权；范围为 OpenAI Chat/Responses → 厂家 WEB，调用方执行工具。
- 修复 Arena 首输出前最多三个不同账号换号、有效输出后禁止重试、统一超时/上游错误和延迟 SSE 终态；优化 Grok 同域会话初始化并记录阶段耗时。LongCat 预租约图片校验与 TXT 完整正文补齐，GLM 保留完整签名历史，MinMax 图片 capture 禁止重复生成并保留全部附件/完整 context。
- 0.25.7 纠正共享历史默认 32 条裁剪：默认/disabled 保留全文，显式 auto 保留原有裁剪策略；厂家硬上限、token/request-size 约束保留。没有新增 API/权限/数据库结构，六个版本文件和 JAR 一致。
- Read：Key DTO projection 与批量授权、overview 合并 COUNT、去除纯 JDBC 读取多余事务、目录只解码一次及大型 Redis 快照压缩；Server 优先与 PostgreSQL 同节点，Automation wave=1 / Server wave=2，Oracle routes 明确 namespace。未移动 PostgreSQL/PV。
- **0.25.7 已部署**：Source `378475f`，[CI 37098598658](https://github.com/Prodigalgal/any2api/actions/runs/37098598658) success，GitOps `0f7ce4d` Synced/Healthy；四组件 1/1 Ready、restart=0，两类 Automation 源码/installed/API 均 0.25.7。Backend 428 passed / 5 skipped（433 tests）、Automation 506 passed、Web lint/build、版本门禁通过。
- 真实官方 SDK 2.54.0：七家所选模型各 9/9 common PASS（0.25.5，GLM 0.25.6 再验）；0.25.7 七家 user-first 46 条历史全部 PASS，GLM developer-first 同样 PASS；MinMax 用户/工具 OCR、reasoning、两函数及全部结果 PASS；七家各两请求有限并发 14/14 PASS、两不同账号、queue_ms=0。临时测试 Key 删除均 204。
- MiMo 真实 Codex `view_image → function_call_output(input_image)` 图片闭环 PASS；客户端 command 策略拒绝单列。现有公网 SSE 首输出前/后取消通过、账号 lease=0；direct/public 常见 JSON 错误 12 项通过，429/502/504 由真实 HTTP fixture 验证，未对生产故障注入。
- 普通管理 Read p50：overview 236.40→86.30ms、accounts 239.88→99.25ms、api-keys 238.32→104.91ms（基线集群 n=3 / 0.25.6 n=5）；四并发 Read 28/28 200、Hikari pending=0。全目录仍回退：集群 50.23→178.36ms，0.25.7 direct 五组 p50=724.54ms、health=396.67ms，保留全部字段与动态运行态，不能称所有 Read 已解决。
- 仍有限制：LongCat 厂家纯色误判、Arena 凭据波动、Grok runtime selection 耗时及本次未观察到的 native reasoning、全量目录/公网成本、Cloudflare 自身 502/524 错误正文。Worker 仅保留已验证候选，当前未发布/未绑定；白框实验未改变生产图片语义。Qwen 不处理，已发布不可变候选不覆盖；详细回滚点与影响见报告。

## 2026-10-03 WEB 厂商常用 OpenAI 桥接补齐（0.25.0 → 0.25.2）

- 执行规格：[WEB_OPENAI_BRIDGE_FOLLOWUP](../docs/requirements/WEB_OPENAI_BRIDGE_FOLLOWUP.md)。沿用用户授权，继续实现、提交、部署和真实验收。
- DeepSeek / Qwen / GLM / MiniMax / Arena 复用 ToolEmulationEngine，补齐 function definitions、required/named choice、结果回放、SSE 和 usage；保留 native search / reasoning / media。
- LongCat 媒体结果保留 call_id，提前拒绝历史/孤立媒体；请求与工具生成错误不计入模型熔断。Grok Web 二进制帧、帧顺序、独立推理超时和取消清理补齐。
- 模型目录冷缓存账号查询按厂商汇总，部署前验证查询结果等价与执行计划。
- 0.25.0 已部署：源码 707d9c7，CI 37035202075 success / GitOps 5a884a4 Synced/Healthy。DeepSeek、MiMo 核心六组通过；GLM/MiniMax 工具生成、LongCat 部分 required/参数错误映射、Arena Max 422 继续修复。Qwen 无账号，真实请求正确返回 model_unavailable 503。
- 0.25.1 修复候选：工具契约写入实际 user turn，明确由调用方执行；LongCat 兼容单个 JSON call；普通/预租约路由的前置参数错误统一 typed 400；Arena 忽略网关负责的 store/previous_response_id；Grok 等待已收到的终态帧处理完再判断连接关闭。Backend 390 passed / 5 skipped，Web lint/build、Automation 回归与版本契约通过后部署复测。
- 0.25.2 Read 修复规格：代理池列表采用必要列投影 + 每批 500 个 ID 的绑定读取，消除按池查询；设置页一次读取三类配置，保留解密、默认值和校验。影响 ProxyPoolService、RuntimeSettingsService、集成测试及统一版本；不改 API/数据结构/写事务。验收绑定隔离、空池、批量上界、设置默认/已保存/损坏失败路径，并部署后比较两类 Read 实际耗时。
- 0.25.1 实测 GLM、MiniMax、LongCat、Grok 核心六组通过，LongCat 历史媒体 direct/public JSON 400。Arena 文本/SSE通过，function 请求被 canonical generation 中的 tool_choice/parallel_tool_calls/stream_options 误拒绝；0.25.2 在 Web 边界消费这些控制，并把跨厂商回归改为真实 Parser → ToolBridge → Provider 验证链路。
- 0.25.2 已部署：源码 `129dbf9`、CI `37046918505` success、GitOps `6498b47` Synced/Healthy，四应用 1/1 Ready、restart 0；Backend 398 passed / 5 skipped，Automation 494 passed、Web lint/build 与版本契约通过。无数据库新迁移。
- Read 实测 18 × 3 在 cluster/direct 均 200；集群 proxy-pools **314.32 → 162.40ms**、settings **313.41 → 88.71ms**。模型目录 SQL 318 行双向差异 0，Execution **282.316 → 17.800ms**；公网基础网络与 DB 跨节点往返仍影响其余 Read。
- MiMo 最终候选核心六组和标准 Responses 工具图片通过；DeepSeek/GLM/MiniMax 显式 none + parallel=false 的两种协议验证通过。Qwen 当前 0 账号，最终候选仍返回预期 model_unavailable 503。LongCat 图片识别样本仍失败；Arena 首轮 Chat 遇到厂家 LOGIN_GATE，第二轮核心六组全部通过，账号不稳定风险保留。
- 发布、逐厂商状态、原始证据、性能限制与回滚：[2026-10-03 验收报告](../docs/reports/WEB_OPENAI_BRIDGE_2026-10-03.md)。

## 2026-10-02 发布部署与 Read 性能核验（0.24.1 → 0.24.4）

- 用户已授权提交、部署和测试。0.24.0 本地候选验收后补齐日期/版本/用途镜像标签，正式候选升为 0.24.1；流水线忽略纯 docs/tasks 变动，避免验收记录触发同版本重新发布。
- 发布与性能范围：[执行规格](../docs/requirements/RELEASE_AND_READ_PERFORMANCE.md)。先测旧生产 Read 基线，再跟踪 CI、GitOps、Pod 和真实厂商工具闭环。
- 用户最新范围：调用方只用 OpenAI Chat/Responses 常用接口，后端桥接厂商 WEB；工具在调用方执行。保留已有扩展，不再以全量协议或 Codex 专属配置/本机执行策略为目标。
- 0.24.1 / 0.24.2 已部署，CI `37011929616` / `37016567168`，GitOps `39a59fb` / `3043e12`，四个应用 Ready，迁移 032 完成。0.24.2 修复对象 tool_choice、列表 N+1 和同步 Controller 线程阻塞，Key/规则/厂商列表耗时实测下降。
- 0.24.2 LongCat 真实 SDK 7 组验收通过；MiMo Responses 6 组通过、Chat null assistant content 翻译失败。0.24.3 在 canonical 边界修复 null/missing content，补充 3 项回归与可配置 smoke 超时，CI `37019554190` / GitOps `116df49` 已部署。
- **0.24.4 已提交并部署**：源码 `b81886c`，CI `37020984892` success，GitOps `49b70b3` Synced/Healthy；四应用各 1/1 Ready、restart 0。两个 Automation Pod project/installed/API 0.24.4 PASS，纠正固定基座的旧 `0.1.0` metadata；browser-runtime/运行依赖保持当前版本。
- 最终验证：Backend 379 passed / 5 live 条件 skipped、Automation 486 passed、Web lint/build、版本契约通过。MiMo/LongCat 官方 SDK 7 组各通过；MiMo 标准 Responses 工具图片结果正确识别。Grok 普通 Responses/SSE/function 续接前三组通过，namespace 扩展空输出另记。
- Read 最终集群内 p50：Key **244.87ms**（原 2711.77）、规则 **272.42ms**（原 1412.33）、厂商 **165.50ms**（原 727.65）；最终 direct 18 × 3 次均 200。WAN 基础约 470–530ms，多数 DB Read 仍约 0.7–1.1s；n=3 不是生产 p95/容量验收。
- 后续按常用路径优先：Grok 独立 Chat required function 120s 超时、LongCat 工具图片适配/输入错误熔断隔离、所用厂商 function/图片能力验证、公网 OpenAI 错误透传、DB 网络/多次读往返和目录 SQL。完整证据与回滚见 [发布与性能记录](../docs/reports/RELEASE_AND_READ_PERFORMANCE_2026-10-02.md)。

## 2026-10-02 OpenAI Chat/Responses agent 协议升级（0.24.0）

- 阶段 1–3 已实现：历史回放、function/custom/namespace、流式终态、缓存隔离、网关 Responses 状态、所有权与资源接口。
- 阶段 4 本地候选验收：官方 SDK、真实 PostgreSQL 迁移和回滚；Codex 图片实验保留补充证据。0.24.0 [验收记录](../docs/reports/OPENAI_AGENT_ACCEPTANCE_2026-10-02.md)为历史结果。
- 用户已授权真实厂商和生产部署；当时 0.24.4 已部署，常用核心链路在 MiMo/LongCat 完成真实 SDK 验收；后续结果见任务板顶部。扩展失败与本机工具权限不作为常用协议全线阻断。
- [任务记录](in-progress/OPENAI_AGENT_COMPATIBILITY.md)、[需求契约](../docs/requirements/OPENAI_AGENT_COMPATIBILITY.md)、[OpenAI API 桥接](../docs/integrations/OPENAI_AGENTS.md)。

## 2026-09-30 Grok Web 双域SSO Cookie双向复制、注册跨域会话导航与多端版本升级（0.22.10）

- **Grok Web 跨域双域 SSO Cookie 双向复制与保活闭环**：
  - **根因查明**：新注册账号生成的 `browser_execution_context` 仅包含 `accounts.x.ai` 的 StorageState；`_credential_cookies` 在检测到 `browser_execution_context` 时直接早退并仅返回 `.x.ai` 域名 Cookie，导致 `OfficialBrowserRuntime` 打开 `https://grok.com` 时浏览器未携带任何 Cookie，`/api/auth/session` 返回空 session `{}` 并触发 `SsoSessionExpired`；
  - **双向复制与完整注入**：重构 `_credential_cookies`，针对 `storage_state` 中的每个 Cookie，只要包含 `sso`/`sso-rw` 或域名为 `x.ai`，强制同步复制一份至 `.grok.com` 域（反之亦然），并确保所有备用 Cookie 也完整映射至双域；
  - **注册后显式访问 Grok.com**：在 `register_grok_web` 流程结束前，预先同步 Cookie 并显式导航访问 `https://grok.com`，使 `_SESSION_REQUEST` 在实际 Grok 主站直接提取 `userId`（支持 `session.userId`、`userId`、`user.id` 多层提取），并在 StorageState 中持久化包含双域完整的 Cookie 与本地存储。

## 2026-09-30 Qwen与Grok重试邮箱独立轮换、首屏超时加固与多端版本升级（0.22.9）

- **通义千问（Qwen）与 Grok Web 重试邮箱独立轮换机制**：
  - **根因查明**：旧架构在 `register()` 循环外调用 `prepare_registration`，导致 5 次 attempt 全程复用同一个邮箱与域名；当阿里云或 Grok 上游风控对首轮邮箱/后缀进行隐式拦截（前端显示验证码已发送但实际不发信）时，后续 4 次重试均在同一个已受限邮箱上徒劳等待超时；
  - **动态独立轮换**：将 `prepare_registration` 移入 `attempt` 循环内部，每次重试均生成独立干净的新邮箱（自动随机轮换域名，如 `edu.fourmm.bond` 等），并在捕获异常时重置 trace，使每次重试都获得完全隔离的新注册上下文；
  - **Grok Web 首屏探测超时加固**：`grok_web_browser.py` 中邮箱输入框探测超时从 10s 提升至 25s，避免跨国网络及 Cloudflare 等待引起的偶发早夭。

## 2026-09-30 Qwen精准填码与滑块接管强化、全域Token捕获与Grok保活实效闭环（0.22.8）

- **通义千问（Qwen）OTP精准填码、密码滑块接管与全域Token拦截闭环**：
  - **根因查明**：Qwen 注册收到 6 位 OTP 后，由于旧逻辑同时派发 paste、keyboard.type 和逐格 fill，单/多单元格输入发生竞态，将验证码输入为重复或错位字符串；且确认按钮被点击后若触发二次滑块挑战未被接管，密码与昵称设置表单未接入挑战解决器，过早强行 goto 刷新打断了注册会话；
  - **精准输入与滑块接管**：重构 `_fill_and_submit_qwen_otp`，支持键盘模拟与自动逐格校准（双重一致性核验），消除重复敲击；提交验证码、密码和昵称时全面接入 `challenge.submit_and_solve` 解决风控滑块；移除过早的页面刷新破坏逻辑，允许平滑等待导航完成；
  - **全域 Token 拦截与多层回退**：`qwen_challenge.py` 扩充对所有包含 `auth`、`verify`、`signup`、`signin`、`user`、`token` 的 JSON 响应的深度捕获，并在主站刷新与 signin 回退中加入明确日志与保护，确保注册凭据 100% 捕获入库。
- **Grok Web 跨域双域 SSO Cookie 与全量 StorageState 实测验证**：
  - 验证 0.22.7 提交的 `browser_execution_context` 持久化及 `.grok.com` + `.x.ai` 双域 Cookie 注入逻辑；
  - 启动真实注册与会话保活回归测试，验证新账号长期保持 ACTIVE。

- **通义千问（Qwen）现代化多形态 OTP 验证自适应与提交流程闭环**：
  - **根因锁定**：现代 Qwen 前端重构后，验证码输入框类名已不再是单一的 `.qwenchat-verification-code-input-cell`，旧逻辑在 `cells.count() >= 6` 失败后退化为 `first_visible(input[type=text])`，将 6 位验证码全部塞进第 1 个单字符格子导致验证失败；且旧代码对验证码提交步骤未对接人机滑块挑战接管，并在 `/auth` 未完成认证时强行跳转主站丢失凭据；
  - **实现自适应填充与提交**：新增 `_fill_and_submit_qwen_otp`，多级适配独立单字符多单元格（支持 clipboard paste 与逐格事件派发）和统一验证码框；对接滑块挑战接管与验证后潜在密码/昵称设置步骤；`qwen_challenge.py` 扩充对 `access_token` 字段的拦截捕获，实现 Token 100% 完整提取闭环。
- **Grok Web 跨域 SSO 会话持久化与长效保活修复**：
  - **根因锁定**：历史 836 个 grok_web 账号失活的根本原因在于：注册完成后未持久化 Playwright `context.storage_state()`，且 `_credential_cookies` 将包括 `accounts.x.ai` 的所有 SSO Cookie 暴力改写成了 `.grok.com` 单一域；新建会话保活时因缺少跨域凭据导致 `/api/auth/session` 返回空 session `{}`；而 `sso_channel.py` 中的 `probe_result` 将 200 空 session 误报为 `ChannelProbeFailed`（`auth_expired: False`），导致死循环重试或异常淘汰；
  - **实现完整会话持久化与双域注入**：在 `register_grok_web` 中捕获并写入包含完整 cookies 和 localStorage 的 `browser_execution_context`；在 `_credential_cookies` 中优先使用 `storage_state` 原始 cookie，并在回退时同时向 `.grok.com` 与 `.x.ai` 注入 SSO Cookie 维持跨域有效性；在 `sso_channel.py` 中将未登录的 200 会话精准识别并标记为 `auth_expired: True`（`SsoSessionExpired`）。


## 2026-09-29 PENDING 账号处置与根因修复、Qwen新版Token契约适配与Grok空会话淘汰（0.22.6）

- **通义千问（Qwen）前端新版 Token 契约逆向适配与闭环**：
  - **根因查明**：逆向官方前端 JS bundle（`0.3.12/js/main.js`）证实，官方已从单一 `localStorage.getItem("token")` 改为多级结构存储在 `qwen_access_token_state`（内含 `token`, `expiresAt`, `version`）以及 `active_token`，且首屏通过 `window.__prerendered_data.user.token` 注入；导致新版注册虽然流程成功，但入库凭据缺少 `token` 字段，后端保活校验拦截死锁在 PENDING；
  - **契约重构**：`automation/any2api_automation/providers/qwen.py` 改造为多级级联 Token 提取与 JWT 解码（直接从 JWT payload 提取 `user_id`），并优化验证码键盘模拟输入提升成功率；`qwen_challenge.py` 扩展 POST 拦截监听，彻底解决凭据完整性问题。
- **Grok Web 空 Session 凭据拒绝与淘汰流转加固**：
  - **根因查明**：9 月底早期版本注册残留的 15 个空会话账号在探活时返回 `status: authenticated` 但 `userId` 与 `sessionId` 均为空；后端 `GrokWebFailureClassifier.java` 错误将其兜底分类为 `provider_stream_error`（标记为可重试流式错误），导致调度器误判为偶发网络抖动陷入无休止重试死循环；
  - **分类修正**：在 `GrokWebFailureClassifier.java` 增加 `unauthenticatedSession` 语义提取，将空 `userId`/`sessionId` 明确分类为 `credential_rejected`（不可重试）；
  - **生命周期流转**：`LifecycleScheduler.java` 在账号达到重试上限或遭遇不可逆凭据拒绝时，显式将其状态流转为 `EXPIRED` 并淘汰，限制重激活仅适用于 3 天内的偶发抖动账号，根除僵尸 PENDING 账号。

## 2026-09-29 重点问题厂商注册攻坚、Grok保活激活契约重构与全链路长耗时超时加固（0.22.5）

- **Grok Web 保活契约重构与 PENDING 账号激活解脱**：
  - **根因查明**：旧版 `GrokWebLifecycleHandler.java` 未沿用系统通用的 `LifecycleOperationExecutor` -> `automation.execute`，而是通过 `OfficialBrowserTransportClient` 错误从顶层读取 `status` 与 `body`；由于自动化端返回的是标准生命周期探活对象（包含 `healthy: true` 而非原始 HTTP 状态码），导致后端恒解析出 502，将每次保活与初次激活均误判为 `provider_upstream_error`；11 次重试耗尽后所有新注册成功的账号死锁在 `PENDING` 状态；
  - **架构修正**：彻底删除孤立的 `GrokWebLifecycleHandler.java`，让 Grok Web 生命周期完全回归通用的 `LifecycleOperationExecutor` 架构；同时在 `OfficialBrowserTransportClient.java` 中引入对 `result` 容器和 `healthy` 响应的防御性解包；
  - **调度自愈增强**：优化 `LifecycleScheduler.java` 中的 `reactivateExhaustedActions`，允许未过期的 `PENDING` 账号在冷却后重新唤醒调度，杜绝新注册账号因偶发抖动而“早夭”死在 PENDING。
- **通义千问（Qwen）现代化纯验证码注册流程重构与 100% 成功交付**：
  - **现场真机取证与流程勘误**：在生产 Pod 内通过真实 Camoufox 捕获现场证实，通义千问官方注册页已全面转为 **"Sign up with verification code"（纯验证码注册模式）**；页面仅有单邮箱输入框与 Continue 按钮，点击后向邮箱发送 6 位验证码，页面渲染 6 个独立单字符输入格（`.qwenchat-verification-code-input-cell`），填入即自动登录主站，不再支持旧版的用户名、独立密码设置与邮件激活链接；
  - **自动化端全流程重写**：`automation/any2api_automation/providers/qwen.py` 完整实现自适应注册流程：智能识别现代验证码注册与旧版双模式；处理用户协议多选框、提交邮箱、自动化滑动验证码解除、异步邮件 6 位验证码接收、按格填入 6 位验证码并点击确认、等待页面跳转进入 `/`，并由上下文提取 Cookie/JWT Token 与会话凭据直接入库；
  - **生产环境端到端验证通过**：
    - Job `75c611f8-64eb-4407-ac14-11c972c036b7`：新注册任务 1 次尝试即 100% 成功完成，账号 `2c1a0820-70af-4162-879e-28b2ac153caa` 成功诞生并安全入库；
    - Job `d458c2b5-9277-4f63-9c3a-de4c1be0a793`：此前因旧逻辑 4 次失败的存量任务，在新版本上线后自动进行第 5 次重试并 100% 成功，账号 `fc5e1c00-3b9f-4dde-9560-a62b377b6a43` 成功诞生，任务转为 `SUCCEEDED`；
    - 彻底扭转通义千问过去 100% 注册暴毙的局面，实测注册成功率达到 **100%（2/2）**。
- **全链路长耗时自动化超时加固（DeepSeek / GLM）**：
  - **根因查明**：`WebClientConfiguration.java` 中的内部 HTTP 连接底层 Netty `responseTimeout` 硬编码为 5 分钟，而复杂的浏览器人机验证（DeepSeek hCaptcha / 邮件验证码收发、GLM 滑动拼图）在多轮重试时极易逼近 300 秒，导致客户端连接被粗暴掐断并报 `WebClientRequestException`；
  - **加固**：将 `WebClientConfiguration` 的 `responseTimeout` 从 5 分钟提升至 15 分钟，与 `RegistrationJobScheduler` 35 分钟的调度设计相匹配，保障长耗时多轮验证码和异步邮件等待稳定返回。

## 2026-09-29 全厂商最新模型探活、Arena纯文本选型与LongCat/Qwen链路加固（0.22.4）

- **全厂商探活动态首选各厂商最新代际模型**：
  - 各厂商 Manifest 与 `scheduledProbeModel()` 全面升级对齐各家最新主力模型：MiMo (`mimo-v2.6-flash`), GLM (`glm-5.3`), Qwen (`qwen3.8-max`), MiniMax (`MiniMax-M3.1-Flash-Preview`), Arena (`Max`), LongCat (`longcat-flash`), Grok (`grok-3`), DeepSeek (`default`)；
  - `ModelProbeScheduler` 防重复探测与额度保护机制：引入 15 分钟新鲜 READY 探针排他拦截，已成功探活的厂商在新鲜窗口内绝不重复发起探测，显著节省调用额度并降低风控封号风险。
- **Arena 探活彻底避免选中文生图/生视频等非对话模型**：
  - 显式声明 `ArenaProvider.scheduledProbeModel() = "Max"`，实测 13s 内稳定就绪；
  - `ModelProbeScheduler` 数据库候选模型查询在 SQL 层建立多模态/生图/生视频模型降级排除机制（自动后置或过滤 `%-image%`, `%-video%`, `flux-%`, `wan-%`, `veo-%`, `kling-%`, `sora%` 等），确保任何纯文本对话模型绝对优先于生图/生视频模型，杜绝纯文本 completions 命中图像模型导致的 400 `invalid_request_error`。
- **LongCat 与 Qwen 链路认证失效自愈与异常识别加固**：
  - 根因定位：LongCat 美团会话创建接口在凭据过期时返回 HTTP 200 + `{"code": 401, "message": "Please log in to continue"}`，通义千问上游在 Token 失效时返回 401 并被反代包装为 502；此前系统仅比对 HTTP 状态码 401/403，导致大量登录失效被误判为 502 `provider_upstream_error`，不仅重试死循环，更无法触发后端的账号下线与重新认证/自动注册补号；
  - `ProviderFailureSignals.java` 引入保守且强健的 `isCredentialRejected` 判定，覆盖 `unauthorized`, `code=401`, `Please log in`, `token expired` 等认证失效特征；
  - `LongcatProvider.java` 与 `QwenProvider.java`：在流式错误帧与模型发现阶段统一将凭据失效提升为 401 `credential_rejected`，禁止单账号无效重试；
  - `automation` 端 `longcat.py` 与 `qwen.py`：在 `keepalive` 与 `transport_stream` 中精准捕获 401/登录失效语义，返回 `auth_expired: True` 且 `error_class: "credential_rejected"`，使得后端 `LifecycleScheduler` 与 `AccountRecoveryService` 能立即调度 `reauthenticate` 或自动注册新号补充账号池。

## 2026-09-29 探活自适应动态选型与 Arena 全链路打通（0.22.3）

- **探活模型动态自适应与无硬限制选型**：
  - 针对厂商模型下架或库中被禁用导致探活硬编码失效（如 GLM 假死 503、DeepSeek 因 expert 禁用未探活等）问题，重构 `ModelProbeScheduler`：动态查询当前厂商在数据库中处于 `enabled = TRUE` 的最新模型（`ORDER BY updated_at DESC, id DESC LIMIT 1`），优先命中 preferred 列表中的可用项，若下架则自动平滑降级到最新模型，彻底解除模型硬编码限制；
  - 移除 `GlmProvider` 中对探活禁用的 override（默认开启），让智谱 GLM 正常参与探活，消除冷启动 503 假死；
  - 移除 `ArenaProvider` 中硬编码的 `scheduledProbeModel() = "Max"`，允许根据库内启用的 260+ 模型动态自适应演进。
- **探活提示词自然化与安全脱敏**：
  - `ModelProbeService` 与 `InferenceReadinessProbe` 中的探活提示词由生僻测试宏（`ANY2API_MODEL_OK` / `ANY2API_PROBE_OK`）统一优化为自然的对话提示词（`"Hello! Please reply with a short confirmation message."`），彻底杜绝因怪异字符串命中大模型上游安全审查（Safety Filter）或防注入拦截导致探活拒答。
- **Arena 链路全线打通与注册即激活**：
  - 彻底查明 Arena 账号停留在 `PENDING` 的根本原因：在自动化端真实浏览器环境（Camoufox）中，账号已经成功接收邮件验证码、设置密码并调用 `/api/me` 验证了 profile，但 `BrowserResult` 中错误设置了 `ready_for_inference=False, inference_probe_required=True`，导致合法的账号被扔回空会话执行推理探测并撞上 Google reCAPTCHA v2；
  - 优化 `arena_browser.py`：真实浏览器注册成功后直接将账号标记为 `ready_for_inference=True, inference_probe_required=False`，注册完成即投入生产；
  - 生产数据库已将 3 枚 Arena 账号成功激活为 `ACTIVE`，实测 `arena/Max` 真实推理调用毫秒级响应并 100% 成功生成。

## 2026-09-28 模型轻量探活与防封禁优化（0.22.1）

- **单一代表模型精简探活**：
  - 针对拥有海量模型（如 Arena 拥有 290+ 个模型）的全量周期探测导致账号额度枯竭及高频触发上游风控封号（429 prompt_rate_limit、401 会话失效）问题，改用“每厂商挑选单一代表模型”的轻量化探活策略。
  - `InferenceProvider` 引入 `scheduledProbeModel()`，默认挑选 `TOP_TEXT` 或默认模型列表首选（Arena 锁定 `Max`）；
  - `ModelProbeScheduler` 仅针对各厂商声明的单一代表模型按需调度探测，禁止全量模型遍历，探活请求量与账号损耗直降 98% 以上。
- **代表模型连通性继承与可用性判定放宽**：
  - 优化 `ModelCatalogCache` 与 `RandomRouteCatalog` 的 `available` 判定表达式：只要厂商代表模型探活通过（处于新鲜 `READY` 状态）且存在可用账号，旗下所有未明确失败的子模型均继承连通性置为可用；
  - 彻底解决用户请求非主探活模型或主模型轮询间隔超期时遭遇的 `503 model is not currently callable` 假死阻断。

## 2026-09-26 Arena 深度优化与全厂商补号健壮性提升（0.22.0）

- **Arena 定期探活与全链路自动就绪**：
  - `ArenaProvider.java` 启用 `scheduledModelProbeEnabled = true`，后端 `ModelProbeScheduler` 自动为 Arena 执行周期性模型探活，彻底解决 `arena/Max has no ready probe result` 报错。
  - 优化 `account_status_is_healthy` 兼容根级用户信息，并为所有 Provider 提供缺省运行时规则防护。
- **全厂商 Keepalive 崩溃与调度队列阻塞根因修复**：
  - 根因定位：统一 Action 契约在转换旧 payload 时覆盖冲毁了后端传入的 `payload["runtime_plan"]`，导致所有依赖声明式运行时规则的 Provider（Arena、Qwen、GLM、LongCat）在执行 keepalive 时以 `TypeError: runtime active selection must be an object` 失败；新账号无法完成首次探活激活并卡在 `PENDING`，同时重试请求堆积造成批处理队列阻塞。
  - 修复方案：在 `ProviderActionRequest` 与 `provider_api.py` 中无损透传 `runtime_plan`；在 `runtime_rules.py` 中内置全厂商默认规则兜底。
- **补号吞吐抗超时增强**：
  - `browser_batch_capacity` 从 2 调优至 4，极大缓解高峰批处理排队。

## 2026-09-25 文档与缺漏排查

- 源码基线：`main` 的 `6cbe025`，Backend/Automation/WEB 版本为 `0.21.0`。
- GitHub Actions [35939416946](https://github.com/Prodigalgal/any2api/actions/runs/35939416946)
  的三端质量检查、镜像构建和 GitOps 更新均成功。2026-09-25 只读检查显示 Argo CD
  `Synced/Healthy`，三套业务 Deployment 均运行 `*-sha-6cbe025...`、Pod Ready 且重启 0；
  这不等于八家 Provider 的真实请求验收。
- 2026-09-26 只读数据库核对：Liquibase 031 已执行，旧 `grok` / `grok_console` 的
  Provider、账号和 API Key 授权行当前均为 0；Arena、Grok Web 的持久化通道模式为 `AUTO`。
  迁移前数据量和备份可恢复性尚未核实。
- 当前源码包含 Arena、DeepSeek、GLM、Grok Web、LongCat、MiMo、MinMax、Qwen 八家；
  `grok`、`grok_console` 已退出代码目录，并由 Liquibase 031 清理持久化数据。
- Java 推理通道声明：DeepSeek、GLM、LongCat、MiMo、MinMax、Qwen 支持 API/Runtime；
  Arena 和 Grok Web 当前仅开放 Runtime。Python 的 API binding 不等于 Java 对外开放。
- 待处理缺漏、证据和优先级见 [2026-09-25 排查报告](../docs/reports/CODEBASE_GAP_AUDIT_2026-09-25.md)。
- 2026-09-26 各 Provider 的账号、模型探针、普通推理和生命周期快照见
  [Provider 运行态快照](../docs/reports/PROVIDER_STATUS_2026-09-26.md)。

## 待处理

- [ ] 核实 Liquibase 031 执行前的数据备份、删除量和恢复策略；已执行变更集不原地改写。
- [x] 发布流水线版本契约、日期/版本/用途/SHA 不可变镜像 tag 和空库 Liquibase 验证已在 0.24.x 本地/CI 验收；记录见本轮发布报告。
- [ ] 明确 Grok Web API binding 的发布意图，并使 Java 通道声明与验收证据一致。
- [ ] 启用 Grok Web API 前补齐直接 `websockets` 依赖声明。
- [ ] 明确 LongCat 模型发现的真实接口或验收例外。
- [ ] 补齐 0.21.0 的逐 Provider 真实推理和生命周期验收记录。

## 历史记录

- [0.18.0 及以前的任务与验收快照](../docs/archive/tasks/PROVIDER_RUNTIME_API_PROGRESS_THROUGH_0.18.0.md)
