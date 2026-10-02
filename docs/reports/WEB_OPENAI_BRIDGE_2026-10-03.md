# 常用 OpenAI API 到厂商 WEB 的桥接与 Read 优化（2026-10-03）

## 范围与证据口径

调用方使用 OpenAI Chat Completions / Responses，后端桥接八家现有 WEB 通道。工具由调用方执行；本轮扩展普通 function，不增加厂商官方 API 或新的协议簇。原有 namespace/custom、搜索、推理、媒体和状态实现保留，是否可用仍由具体 Provider/模型契约决定。

本轮依次发布 0.25.0、0.25.1、0.25.2，每次修改源码占用新的 SemVer 和不可变制品。代码支持、离线回归、真实模型验收和部署健康分别记录；一款模型通过不能外推到该厂家所有模型。

## 发布记录

| 版本 | 源码 | CI | GitOps | 结果 |
|---|---|---|---|---|
| 0.25.0 | `707d9c7037198154988bf9c562b0e6a407a438fa` | [37035202075](https://github.com/Prodigalgal/any2api/actions/runs/37035202075) | `5a884a4921629e0bf3da1c263acad74c17da1a5c` | 构建、部署、健康检查通过；实测问题进入下一候选 |
| 0.25.1 | `8c1fdb83c93f93c7b8b0ae48daadb6761da61a1e` | [37043443022](https://github.com/Prodigalgal/any2api/actions/runs/37043443022) | `b1055fee0ee116092366af80720c3b7f465def64` | Synced / Healthy，四个应用 1/1 Ready、restart 0 |
| 0.25.2 | `129dbf9b7b3be71936384d36796d639f81a1a9df` | [37046918505](https://github.com/Prodigalgal/any2api/actions/runs/37046918505) | `6498b47c2561f24a1afc97a41d965b27b6a656ca` | CI success；Synced / Healthy，四应用 1/1 Ready、restart 0；Arena / MiMo 六组通过，Read 优化实测通过 |

镜像仓库 `docker.io/speedproxy/any2api`，四个组件为 `server`、`web`、`automation`、`automation-arena`。统一 tag 格式：`<component>-20261002-v<version>-release-<完整源码 SHA>`；日期按 CI 的 UTC 时间生成。本轮没有数据库结构迁移，保留既有 migration 032 和业务 tag 0.24.0。

0.25.2 运行态制品：

| 组件 / Pod | sha256 digest |
|---|---|
| server / `any2api-server-5fc745fdc8-5hdzl` | `58b60a562e2f13b3b94fd5627b61088d1f961f38b563bef89c91ab9bf74a7417` |
| web / `any2api-web-cb9684446-xf52k` | `2731f3ad60cc07a18ee636fd82c0a5aab22852764845419e801885874c783acd` |
| automation / `any2api-automation-7bb7bc55d8-9vg46` | `07ec972805fee1fe58835f8d365271bd5d8c3592a624a69cd40320125d95ee61` |
| automation-arena / `any2api-automation-arena-85f488fbf7-4z7jd` | `49cdde195bdca87a261786f3e7741a1b11cfd5209b218a98640649e296a4d997` |

两个 Automation Pod 的 project/installed/API 均为 0.25.2，通过 `check_runtime_version.py`；数据库 changelog 仍为 36 条，最后一条 `032-002-tag | 0.24.0`。既有 browser-runtime 固定基座没有重新发布。

## Changelog

### 新增能力：五家 WEB function 桥接（0.25.0）

- DeepSeek / Qwen / GLM / MiniMax / Arena 的 Provider 接入已有 `ToolEmulationEngine`，manifest 明确声明 `FUNCTION_TOOLS: EMULATED`；MiMo / LongCat / Grok 继续使用原有工具实现。
- 对普通 function definitions、auto/none/required/指定函数、参数 JSON、call_id、结果回放与 canonical SSE 做统一处理。工具请求原件保持不变，交给 WEB 的副本移除网关负责的工具控制；原生搜索等上游工具保留。
- required/named 未生成合法调用时返回明确的 `tool_call_generation_failed`，不把普通文本伪装成工具调用。限制捕获大小，拒绝不合法参数和违反 parallel=false 的调用。
- 新增 `openai_agent_smoke.py --core`，常用协议的六组验收独立于 namespace/custom 扩展。默认完整脚本仍保留已有扩展测试。

### 可靠性与兼容修复（0.25.0 → 0.25.2）

- `ToolEmulationEngine` 的工具契约写入实际最后一个 user turn，兼容 WEB 忽略独立 system/developer 指令的情况；保留媒体数组，明确外部调用方负责执行函数。
- `ArenaRequestMapper` 与 emulation 边界消费 canonical `generation` 中重复存在的 `tool_choice`、`parallel_tool_calls`、`stream_options`；Arena Runtime 忽略网关自有 `store/previous_response_id`。真正未支持的生成参数仍返回 typed 400。
- `InferenceCoordinator` 在普通和预租约路径统一将前置参数校验错误映射为 OpenAI JSON 400。错误输入、工具生成失败和调用方取消不计入模型熔断；真实上游限流/失败仍按已有规则处理。
- LongCat 支持尾部匹配工具结果中的媒体上传，保留 call_id、附件顺序和原有媒体限制；历史/孤立媒体在租用账号之前明确拒绝。支持单个平面 JSON function call，同时保留已有格式。
- Grok Web 补齐 ArrayBuffer/Blob 解码、串行帧处理、连接关闭时先消费已收终态、独立推理预算、AbortController 取消和清理；保留仅含事件类型/通道计数的诊断日志。
- `WebFunctionBridgeTest` 从手工 canonical fixture 改为真实 `Parser → OpenAiToolBridge → Provider → SemanticCommand` 链，覆盖两种协议、指定函数、parallel=false、usage 与 none，避免遗漏 canonical 参数副本。

### Read 性能（0.25.0 / 0.25.2）

- `ModelCatalogCache` 将每个模型反复扫描账号，改为一次 materialized eligible accounts、按厂商汇总，再关联模型和冷却统计；保留原有账号过滤和 usage window 语义。
- `ProxyPoolService.list()` 使用必要列投影，先查池、再按每批 500 IDs 批量读取绑定，正常两次 SQL、空列表一次、501 个池三次；保留禁用/未绑定池、scope 和元数据，不读取加密 payload。
- `RuntimeSettingsService.get()` 一次读取三类配置，继续按相同 AAD 解密并使用原有默认值/校验；不引入缓存，更新立即可见。
- 移除以上纯 JDBC Read 的不必要事务往返，写事务保留。无 API、schema、权限、加密 key 或部署拓扑变化。

## 验证

0.25.2 本地 Backend：**403 tests，398 passed，5 skipped，0 failures/errors**；包含真实 PostgreSQL/Liquibase 的七项 `AdminReadBatchIntegrationTest`，验证空列表、绑定隔离、501 个池批量上界、缺省配置、与逐项读取等价、即时更新、加密损坏和无效配置仍失败。`test bootJar`、六处源码版本及 JAR `Implementation-Version` 检查通过。

Automation 语义变更在 0.25.1 完成 **494 passed**；0.25.2 只同步版本，ruff check/format、uv lock 检查通过。Web lint/build 通过。最终 CI 的 Backend / Automation / Web、四个镜像和 GitOps 全部 success。

官方 Python SDK **2.54.0**，模型范围临时 Key，Chat/Responses、TOOL_CALLING、AUTO；`--core --no-reasoning --client-timeout 180`，无自动重试掩盖失败。实际六组为：

1. 模型发现。
2. Responses 非流式文本。
3. Responses SSE 增量及 SDK 最终重建。
4. stored required function 闭环、`demo.txt` 结果回传、retrieve/input_items 分页/delete、跨 Key 404。
5. Chat required function 闭环、`demo.txt` 回传、普通 SSE 和 usage。
6. 普通 function SSE、指定函数、parallel=false、参数增量拼接为合法 JSON。

每个临时 Key 均在 finally 删除并记录 HTTP 204，凭据只在内存使用；日志/报告不保存 Key。下表记录完成所有六组的成功报告；失败尝试与账号/媒体风险另外保留，未用重跑覆盖原始日志。

| 厂商 | 验收模型 | 六组结果 | 样本首文本增量 | 版本 |
|---|---|---|---|---|
| DeepSeek | `deepseek/default` | 6/6 PASS | 38.73s | 0.25.1 |
| GLM | `glm/glm-5.2` | 6/6 PASS | 38.90s | 0.25.1 |
| MiniMax | `minmax/MiniMax-M3.1-Flash-Preview` | 6/6 PASS | 8.00s | 0.25.1 |
| LongCat | `longcat/longcat-flash` | 6/6 PASS | 7.75s | 0.25.1 |
| Grok Web | `grok_web/grok-3` | 6/6 PASS | 126.62s | 0.25.1 |
| MiMo | `mimo/mimo-v2.6-flash` | 6/6 PASS | 8.06s | 0.25.2 |
| Arena | `arena/Max` | 6/6 PASS；首轮登录失败后单独第二轮通过 | 18.24s | 0.25.2 |
| Qwen | `qwen/qwen3.8-omni-flash` | discovery 通过；无账号，推理 503 `model_unavailable` | 无 | 0.25.2 |

样本增量包括网关/WEB/runtime 的启动和等待时间，不是厂商模型纯推理耗时，也不是统计 SLA。0.25.2 另外复测受影响五家在显式 `tool_choice:none` + parallel=false 下的 Responses 文本和 Chat SSE/usage：DeepSeek/GLM/MiniMax 两项均通过；Arena 首轮 Chat 遇到 LOGIN_GATE，独立第二轮两项均通过；Qwen 仅离线契约，没有账号时不伪造 E2E 通过。

Arena 首轮 Chat required 请求 `2f401eea-7196-4d2f-92f5-b3c83907db3d`，显式 none Chat 请求 `063986df-b107-4d78-9aaf-1c0003058394`，均为厂家 HTTP 401 `LOGIN_GATE`，网关返回 502 `credential_rejected`（不冒充调用方 Key 无效）。请求使用不同账号，已看到既有认证失败处置与自动恢复推进。第二次完整 SDK 运行单独记录 `v0252-attempt2`，六组全部通过；说明桥接可完成，但不能据此宣称凭据池稳定。默认 Provider retry 不重试 credential_rejected，本轮没有修改该策略或强制挑选账号。

Qwen 最终候选无账号请求 ID `18881a18-0b15-4192-9fcf-e015031c290f`，503 为预期不可用响应，不能算工具链通过。

### 厂商运行态快照

2026-10-03 本轮查询时，八家均 installed/enabled；账号数为数据库 `status=ACTIVE`，不等于每个账号实时探针或所有模型均可调用：

| 厂商 | ACTIVE 账号 | enabled 模型 |
|---|---:|---:|
| Arena | 121 | 259 |
| DeepSeek | 16 | 1 |
| GLM | 32 | 15 |
| Grok Web | 8 | 13 |
| LongCat | 67 | 5 |
| MiMo | 97 | 14 |
| MiniMax | 39 | 4 |
| Qwen | 0 | 7 |

### LongCat 媒体与错误边界

- 0.25.1 历史媒体连续三次 direct 返回 **400 `invalid_request_error`**，public 入口也返回 **400 `application/json`**。修复前同类错误曾被映射成 502。真实请求 ID：`50424ddc-cd50-424c-935a-9af4191ea6e4`、`06483754-d820-459a-92ea-aa9addef8a50`、`cead67b1-b6bf-4fc2-81b0-81932fe2c7ee`；public `f775ed08-b2ee-4197-8b25-ec55fff1c1e3`。
- 工具尾部图片上传可完成请求，但 **图片识别精度未通过**。合成 PNG 为 RGB(240,128,64)，期望颜色没有放进 prompt；32px 工具结果及普通用户图片被答成 pink/magenta，256px 普通图片被答成 red。Responses completed 不能记为图片 E2E PASS。
- 0.25.2 再测 256px 工具图片，`resp_d4a296245f5c4032a223e8ca31ea66c1` completed / 10.129s，回答 bright pink，仍 FAIL；历史媒体三个请求继续返回 typed 400。
- 官方 LongCat 公开前端 assets 中的 upload `url/key → fileUrl/fileKey`、file metadata、`multiModal` agent、session/chat 请求字段与当前桥接一致；没有足够证据把识别失败归因到具体桥接字段。后续需定位厂家媒体处理/模型输入，不能通过提示期望答案制造通过。
- MiMo 0.25.2 标准 Responses 工具图片复测 **PASS**：`resp_d5ed090467794152b94e80d746978a78` completed / 17.141s，正确回答 orange，期望颜色未放进 prompt。它验证该模型的这个合成图片结果回传样本，不代表所有图片任务精度。

## Read 性能证据

### 模型目录 SQL

在同一真实 PostgreSQL 上，旧/新查询均返回 **318 行**，`EXCEPT ALL` 双向差异 **0**。EXPLAIN ANALYZE：

| 指标 | 修改前 | 修改后 |
|---|---:|---:|
| Execution Time | 282.316ms | 17.800ms |
| Shared buffer hits | 85,354 | 2,043 |
| Planning Time | 7.760ms | 9.269ms |

执行时间下降约 93.7%；这是冷目录 SQL 单次同库比较，不等于整个 `/v1/models` HTTP 延迟下降同样比例。

### HTTP Read

同一现有集群从 Automation Pod 访问 `http://any2api-server:8080`，及 Windows direct 入口，每个 18 个 Read endpoint × 3 次。0.25.1 部署前与 0.25.2 部署后均全部 HTTP 200。n=3 只作低并发比较，脚本 `p95_ms` 是该小样本最大值，不能当作生产 p95 或压测结论。

| 接口 | 0.25.1 集群 p50 | 0.25.1 direct p50 | 0.25.2 集群 p50 | 0.25.2 direct p50 |
|---|---:|---:|---:|---:|
| proxy-pools | 314.32ms | 725.19ms | **162.40ms** | **560.59ms** |
| settings | 313.41ms | 727.69ms | **88.71ms** | **513.48ms** |

集群内代理池下降约 48.3%，设置页下降约 71.7%；direct 分别下降约 22.7% / 29.4%。这是已部署真实接口结果。

0.25.2 其他 Read 的集群 / direct p50（ms）：

| 接口 | 集群 | direct |
|---|---:|---:|
| healthz | 8.59 | 416.44 |
| readyz | 90.11 | 493.05 |
| session | 7.88 | 405.26 |
| overview | 257.61 | 712.17 |
| providers | 169.73 | 604.62 |
| accounts/page | 260.74 | 686.16 |
| registration-jobs/page | 248.16 | 669.69 |
| registration-schedules/page | 245.67 | 664.70 |
| requests | 253.90 | 647.31 |
| operations | 270.33 | 706.42 |
| api-keys | 257.30 | 647.85 |
| provider-runtime-rules | 264.48 | 698.44 |
| models/limits | 72.40 | 684.25 |
| catalog/providers | 11.18 | 459.50 |
| mimo/v1/models | 90.00 | 482.39 |
| v1/models | 107.62 | 556.96 |

基线集群 `/healthz` 5.42ms，`/v1/models` 78.54ms；direct `/v1/models` 548.49ms。模型目录约 1.50MB decoded、108.9KB gzip wire；目录响应体积仍需考虑客户端需求及缓存。

基线 Hikari pending=0、active=0，CPU 约 7–10%；部署后 Read 采样 pending 仍为 0、active 1–2，CPU 约 11–45%（新进程启动期）。这些样本没有连接池排队或 CPU 打满证据。Server/主 Automation 与 PostgreSQL 在不同节点，已测到约 80ms 级 SQL 网络往返，多次读取和事务增加累计耗时。direct 的 healthz 本身约 416ms，公网还叠加基础网络时间，优化 SQL 不会消除这一段。

### Grok 首输出

0.25.1 真实 Responses SSE 请求 `6eed65d9-93da-4a81-a797-ec9db50cae94`：`duration_ms=128465`、`queue_ms=0`、`account_acquire_ms=739`、`ttfb_ms=125278`、`generation_ms=2443`。SDK 首增量 126.62s。主要等待发生在 WEB/runtime 首输出前，账号排队不是该样本的主要耗时，也与管理 Read SQL 无直接关系；尚未细分页面初始化、连接建立和厂家生成时间。

## 遗留事项与下一步

1. Qwen 缺少可用账号；完成代码/契约桥接，真实 E2E 等现有账号就绪后再验，不新建外部账号。
2. Arena 偶发 LOGIN_GATE；需继续量化有效凭据比例、恢复耗时与首输出前换账号策略，不能把 ACTIVE 数量作为可调用账号数。现有重试不包含 credential_rejected。
3. LongCat 图片上传链路已补齐，图像准确性仍失败；继续定位实际媒体输入与模型行为，验收前不承诺图片 agent 可用。
4. Grok WEB 首输出长且波动；下一步细分 runtime/page/socket/生成阶段，按业务 SLA 决定超时与降级，而不是无限延长客户端超时。
5. DB 网络往返、剩余多 SQL Read 和目录体积还有优化空间；应基于具体接口 query count、同拓扑延迟和负载测试继续，当前未改变 PostgreSQL/PV 布局。
6. public 入口本轮 400 JSON 已验证；Cloudflare 对真实上游 502/504 的 body 是否完整透传仍未完成新的专门验收。无边缘配置写入。
7. 验收范围是每家上述一个模型的常用 function/text；真实模型的原生 search/reasoning、其他媒体、长历史、并发压力和可选 namespace/custom 未在本轮全量 E2E。

## 兼容性与回滚

普通文本、Provider/API 路由、Key 权限、既有工具扩展和写入 API 保持契约；新增五家模拟工具是向后兼容扩展，已按 minor 0.25.0 发布；随后修复按 patch。无新 schema，Read 优化可直接回滚应用镜像。

- 0.25.2 Read 或控制消费出现回归：恢复 0.25.1 四组件不可变 tag，后缀 `20261002-v0.25.1-release-8c1fdb83c93f93c7b8b0ae48daadb6761da61a1e`，但 Arena 的重复 generation 控制拒绝会回归。
- 新增厂家 function 整体出现问题：恢复已验 0.24.4 四组件，后缀 `20261002-v0.24.4-release-b81886c4bb9b938df62bcaf6fe490594aaceff88`；五家新增 function 与本轮优化将撤回，保留既有 migration 032。
- 回滚通过现有 GitOps 指向已发布镜像，不覆盖 tag，不回收候选版本，不操作 PostgreSQL/PV。

## 原始证据

原始报告和日志在 Git 忽略的 `backend/build`，供本工作区核查，不包含凭据：

- 发布：`release-0.25.0-runtime.json`、`release-0.25.1-runtime.json`、`release-0.25.2-runtime.json`。
- 成功六组：`live-v0251-{deepseek,glm,minmax,longcat,grok_web}-sdk-auto-180s-core.json`、`live-v0252-mimo-sdk-auto-180s-core.json`、`live-v0252-attempt2-arena-sdk-auto-180s-core.json`。
- 控制边界：`live-v0252-{deepseek,glm,minmax}-controls.json`、`live-v0252-arena-controls.log`、`live-v0252-attempt2-arena-controls.json`。
- 失败尝试与负向验收：`live-v0252-arena-core.log`、`live-v0252-qwen-core.log`、`live-v0251-longcat-preflight.json`。
- 图片：`live-v0252-mimo-tool-image-32.json`、`live-v0252-longcat-tool-image-256.json`，以及保留的 `live-v0250-longcat-*-image*.json`。
- 性能：`read-v0251-cluster.jsonl`、`read-v0251-direct.json`、`read-v0252-cluster.jsonl`、`read-v0252-direct.json`、`catalog-before-explain.txt` / `catalog-after-explain.txt`。

花括号表示同一命名模式下的独立文件，不是单个实际文件名。成功/失败尝试使用不同 label，不覆盖原始结果。完成后的 docs/tasks 证据提交由 CI paths-ignore 排除，不重建或覆盖已验收 0.25.2 制品。
