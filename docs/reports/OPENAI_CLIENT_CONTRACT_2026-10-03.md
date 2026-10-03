# 统一 OpenAI 客户端契约验收（0.26.0 → 0.26.2）

## 范围与基线

用户要求沿统一 OpenAI 客户端契约与各厂商 WEB 适配继续推进。本轮补齐模型详情和显式严格函数参数；规格见 [执行范围](../requirements/OPENAI_CLIENT_CONTRACT_FOLLOWUP.md)。原生产为 0.25.7，制品源码 378475f；本轮工作树基线 41054fd 为文档提交。原 GitOps 0f7ce4d，执行前 Argo Synced/Healthy、四组件 Ready。

## 已实现

- 统一/厂家 `models.retrieve()` 与模型目录共享字段和运行态；支持带斜线 ID，缺失、禁用厂家、越权返回 `404 model_not_found`，不泄露模型是否存在。
- strict function schema 使用 Jackson 3 的 networknt 3.0.8 校验器，编译缓存上限 128、空闲十分钟失效；输入关键词、引用、大小、深度和展开成本有界。仅局部非循环引用，schema 校验不读取外部资源。
- MiMo、LongCat 及共享工具桥接的明确 strict 拒绝改为统一前置检查；canonical 流在严格工具参数完整、匹配 delta 且符合 schema 后释放工具事件。不合格参数不会发送给客户端，不修补字段或伪造成功。
- 网关 strict 能力独立于旧发现记录，模型缓存命名空间升为 v5，避免滚动时读取旧进程的能力快照。
- 默认 store/strict 行为、Key、权限、原始媒体、搜索/推理和已有 WEB 通道保留。新增向后兼容接口与能力使用 0.26.0，无数据库新迁移。

## 0.26.0 首轮部署与真实验收

Source `77abc11b05361dcf2c58f585b2d029b315be545e`，[CI 37119721839](https://github.com/Prodigalgal/any2api/actions/runs/37119721839) success；GitOps `308e8e7288323c4861869514bab2bfc6f6ae2c56` Synced/Healthy。四组件 1/1 Ready、restart=0，两类 Automation project/installed/API 0.26.0 PASS。镜像后缀 `20261003-v0.26.0-release-77abc11b05361dcf2c58f585b2d029b315be545e`。

使用现有分发 Key、Python SDK 2.54.0、direct HTTPS，推理并发 2；每家五组：模型详情/strict 能力、未知模型 404、非法 strict schema 400、Chat strict/Pydantic/结果续接、Responses strict/SSE/结果续接。

| 厂商/所选模型 | 首轮结果 | 证据与处理 |
|---|---|---|
| Arena / Max | 5/5 PASS | 两协议严格工具参数及结果均通过 |
| DeepSeek / default | 5/5 PASS | 两次闭环分别 156.82 / 210.45s，上游耗时保留 |
| GLM / glm-5.2 | 5/5 PASS | 两协议严格工具参数及结果均通过 |
| Grok Web / grok-3 | 3/5 | Java GrokWebToolProtocol 仍拒绝 strict，修复进入 0.26.1 |
| LongCat / longcat-flash | 3/5 | Automation LongCat helper 仍拒绝 strict，修复进入 0.26.1 |
| MiMo / mimo-v2.6-flash | 5/5 PASS | 两协议严格工具参数及结果均通过 |
| MiniMax / MiniMax-M3.1-Flash-Preview | 5/5 PASS | 两协议严格工具参数及结果均通过 |

额外模型路由检查：七家厂商详情与根/厂商前缀越权模型均符合 200/404；public 的 MiMo 厂商详情/越权也通过。九条直接读取编码 ID 的检查首先收到入口 307，MiMo 补充检查确认跟随后同源 200；不是直接 JSON 200。应用直连编码 ID 另有生产 Spring Security 400，原 controller-only fixture 未覆盖安全链，0.26.1 补齐实际安全链并限定 GET 模型 ID 放行。共享 Envoy 默认行为见 [官方 PathSettings](https://gateway.envoyproxy.io/docs/api/extension_types/#pathsettings)，当前不修改共享 Gateway。

首轮失败和成功证据分别保留在 `backend/build/strict-v0260-*.json`、`strict-v0260-suite.json`、`model-contract-v0260-live.json`，不以复测覆盖首轮失败。

## 本地验证与 0.26.1 修复候选

- Backend：461 项，456 passed / 5 条件 skipped / 0 failure/error，bootJar 构建通过；源码/JAR/Web/Automation 七处版本均为 0.26.0，migration tag 仍为 0.24.0。
- Automation 回归、ruff、Web lint/build 通过。Python SDK 2.54.0 的真实 HTTP + PostgreSQL fixture：既有常用/扩展 12 组通过，新增模型详情、strict/Pydantic Chat、Responses SSE/结果回放和无效严格参数失败 6 组通过。
- 无效严格参数的 fixture 确认收到 `response.failed`，未收到任何 function 参数事件或 function output item。官方 SDK 的 `get_final_response()` 仅接受成功终态，失败测试读取标准 failed 事件的 response，不将 SDK 成功 helper 用于失败终态。
- 0.26.1 只修复首轮遗漏：Grok 使用共享 schema 前置检查，LongCat Automation 保留 schema/调用方执行规则并由网关检查严格参数；生产安全链限定 GET 模型详情 ID 放行编码斜线。SDK fixture 纳入实际 SecurityConfiguration，并覆盖编码 ID 与无认证 401；路径前缀/遍历/双斜线/控制字符拒绝回归已通过。
- 0.26.1 本地门禁：Backend 464 tests / 459 passed / 5 条件 skipped、bootJar；Automation 508 passed / ruff；Web lint/build；源码/产物版本契约全部通过。实际 SecurityConfiguration 的 SDK HTTP fixture：原 12 组和新 7 组通过，含编码 ID、无认证 401、无效严格参数的 failed 终态且无工具参数泄露。SDK 自行编码 model ID，测试不提前编码造成二次编码。
- 0.26.1 已部署并完成七家所选模型复测，结果见下节。

本地证据位于 Git 忽略的 `backend/build/contract-v0260-backend-full.log`、`contract-v0260-local-summary.json`、`strict-v0260-fixture.json`、`common-v0260-fixture.json`。报告不包含 Key、真实用户内容或完整工具参数。

修复候选证据：`contract-v0261-backend-full.log`、`contract-v0261-automation.log`、`contract-v0261-web-*.log`、`contract-v0261-local-summary.json`、`strict-v0261-fixture-final.json`、`common-v0261-fixture.json`。

## 0.26.1 最终部署与真实验收

- 制品源码：`74028a7bb212105e4a3d7c89832e11b1184b6e54`；[CI 37121551123](https://github.com/Prodigalgal/any2api/actions/runs/37121551123) success。Backend/Automation/Web/版本门禁与四镜像发布全部通过。
- GitOps：`2162a82d5ca0a45b5918aaefe2f7d9c298aa5c9d`，验收后再次核验 Argo Synced/Healthy。四应用 Pod 1/1 Ready、restart=0；两个 Automation 的 project/installed/API 均为 0.26.1。镜像后缀统一 `20261003-v0.26.1-release-74028a7bb212105e4a3d7c89832e11b1184b6e54`，四镜像 digest 已记录，未覆盖 0.26.0 制品。

| 组件 | 最终 Pod | Ready / restart |
|---|---|---|
| Server | any2api-server-86f9d67bd-r9vhn | 1/1 / 0 |
| Web | any2api-web-59c75598cd-9g4n2 | 1/1 / 0 |
| Automation | any2api-automation-648db6757b-6zqxs | 1/1 / 0 |
| Arena Automation | any2api-automation-arena-776966df4f-hh77b | 1/1 / 0 |

同一官方 SDK、既有分发 Key、direct HTTPS 与推理并发 2。七家各五组，**35/35 PASS**；包含 28 次实际推理请求，函数参数校验、调用方回传结果和两协议续接均通过。

| 厂商/所选模型 | 最终结果 | Chat 工具闭环秒数 | Responses 工具闭环秒数 |
|---|---|---:|---:|
| Arena / Max | 5/5 PASS | 60.70 | 62.20 |
| DeepSeek / default | 5/5 PASS | 172.79 | 118.86 |
| GLM / glm-5.2 | 5/5 PASS | 93.59 | 81.71 |
| Grok Web / grok-3 | 5/5 PASS | 35.11 | 32.91 |
| LongCat / longcat-flash | 5/5 PASS | 11.27 | 16.38 |
| MiMo / mimo-v2.6-flash | 5/5 PASS | 26.78 | 25.36 |
| MiniMax / MiniMax-M3.1-Flash-Preview | 5/5 PASS | 19.77 | 16.64 |

每个闭环包含生成函数调用和回传结果后的续接，两次请求合计；这是单次功能验收，不是延迟分位数或容量测试。WEB 推理仍可能较慢，不能将 strict 校验通过理解为性能问题全部解决。

- 模型读取：七家 encoded 根路径、厂商详情、根/厂商前缀越权模型，以及 public MiMo 补充检查，**31/31 PASS**。编码路径先同源 307、跟随后 200，其余详情直接 200、越权 404；模型能力与目录一致。
- Qwen 只读目录/详情补查为 200，`available:false`，能力与目录一致；所选旧目录项只声明 hosted search，测试最初误要求不存在的 function strict 字段而出现 KeyError，补查未修改模型数据。未做 Qwen 账号或 function 推理验收，不混入七家 strict 成功数。
- 生产应用安全链经本地 tunnel 读取：encoded ID 直接 200、无效 Key 401、越权模型 404、编码路由前缀/双斜线/遍历/反斜线 400，**8/8 PASS**；共享入口的 307 与应用响应分开记录。
- 验收后七个所选模型 `concurrent=0 / queue_depth=0 / circuit_state=CLOSED`；这里验证模型 guard，未将其表述为所有后台任务或账号租约的完整审计。
- Key、权限、数据库结构与默认存储策略没有变更。测试仅使用现有 Key 和合成内容；本地 fixture 与 tunnel 已关闭，未新增分发 Key。

最终证据：`backend/build/release-0.26.1-ci.json`、`release-0.26.1-runtime.json`、`release-0.26.1-final-runtime.json`、`strict-v0261-suite.json`、`strict-v0261-*.json`、`model-contract-v0261-live-summary.json`、`model-firewall-v0261-live.json`；首轮和测试误判记录保留，不覆写成功率。

## 0.26.2 无参数函数默认值修复

收尾按官方 SDK 的 FunctionDefinition 检查发现无参数函数可省略 parameters，但 canonical bridge 会补开放 object，造成 strict 校验误拒绝。0.26.1 真实 MiMo 请求返回 `400 invalid_request_error / tools.parameters`，request_id `cf276d7a-8d68-4023-9402-314d21af5148`；没有调用上游。

修复候选 0.26.2：strict missing/null parameters 统一为 closed empty object，非严格沿用 open object，显式 schema 不改写。StrictFunctionSchema 与 bridge 共用空参数构造；schema 注册表只在有效严格 schema 校验时初始化，普通或前置非法 schema 不提前加载。新增 parser → canonical schema/事件链回归，SDK 两协议测试 missing/null 默认值。0.26.1 的完整矩阵保持独立记录。

- 0.26.2 本地门禁：Backend 469 tests / 464 passed / 5 条件 skipped / 0 failure/error、bootJar；Automation 508 passed / ruff format/check；Web lint/build；源码七处与 JAR 版本契约全部通过。没有数据库新迁移。
- 实际 HTTP + PostgreSQL + SecurityConfiguration 的官方 SDK 2.54.0 fixture：原 12 组与新增 8 组全部通过。新组覆盖 Chat 省略 parameters、Responses 显式 null、空参数结果、strict/Pydantic、SSE 续接、编码 ID/认证、非法 schema 和不合格上游参数的失败终态。本地 fixture 已关闭。
- 门禁证据：`contract-v0262-backend-full.log`、`contract-v0262-local-summary.json`、`contract-v0262-automation.log`、`contract-v0262-web-*.log`、`strict-v0262-fixture.json`、`common-v0262-fixture.json`。差量仅重测模型能力/404/非法 schema 与两协议无参数调用，0.26.1 完整参数化闭环结果保留独立记录。

### 0.26.2 部署

制品源码 `3c925237abb68de50d3af99d30a6e9f80c9cb67d`；[CI 37124714703](https://github.com/Prodigalgal/any2api/actions/runs/37124714703) success，全部质量门禁、四镜像与 GitOps 更新通过。GitOps `ddb7e6824ba5aa14cae9c61c2f2d7c1d049c5575` 已 Synced/Healthy；四组件 1/1 Ready、restart=0，两类 Automation project/installed/API 0.26.2 PASS。镜像后缀统一为 `20261003-v0.26.2-release-3c925237abb68de50d3af99d30a6e9f80c9cb67d`。

| 组件 | 0.26.2 Pod | Ready / restart |
|---|---|---|
| Server | any2api-server-7dd6b86c6b-vnmz4 | 1/1 / 0 |
| Web | any2api-web-7d8c447ffb-xt794 | 1/1 / 0 |
| Automation | any2api-automation-778f587b6b-hf6kh | 1/1 / 0 |
| Arena Automation | any2api-automation-arena-67c7c79c9f-s4wf7 | 1/1 / 0 |

Server 仍位于 PostgreSQL 同节点 `instance-20251229-0833`。CI 与镜像 digest 证据为 `backend/build/release-0.26.2-ci.json`、`release-0.26.2-runtime.json`。未新增 Key、人工变更账号或数据库结构；既有账号失败计数和自动恢复按正常运行逻辑推进，Worker 未发布。

### 0.26.2 七家差量验收

官方 SDK 2.54.0、现有分发 Key、direct HTTPS、推理并发 2。每家四组：模型详情/strict 能力、缺失模型 404、非法 schema 400、Chat 省略 parameters + Responses 显式 null 的无参数 strict 调用。首轮 **27/28 组通过，六家各 4/4；Arena 3/4**，不是七家全通过。

| 厂商/所选模型 | 差量结果 | 无参数两请求合计秒数 |
|---|---|---:|
| Arena / Max | 3/4，credential_rejected | 首轮 123.49；有限复测 63.10，仍失败 |
| DeepSeek / default | 4/4 PASS | 159.75 |
| GLM / glm-5.2 | 4/4 PASS | 87.36 |
| Grok Web / grok-3 | 4/4 PASS | 30.69 |
| LongCat / longcat-flash | 4/4 PASS | 18.69 |
| MiMo / mimo-v2.6-flash | 4/4 PASS | 61.74 |
| MiniMax / MiniMax-M3.1-Flash-Preview | 4/4 PASS | 15.27 |

首轮包含 14 次客户端推理请求。Arena 首轮 Chat 已通过，Responses 返回 `502 credential_rejected`，request_id `cf011824-4c6a-42b4-9fa0-65e4f42190b3`；一次有限复测在 Chat 阶段同样失败，request_id `d16627d0-beff-406a-9680-b004baf818e7`，未继续执行 Responses。两次失败各已换用三个账号，共六个不同账号，queue_ms=0、有效输出前失败；保持既有重试上限，未通过无限重试凑成功。

只读账号页当时为 125 个 Arena 账号，122 ACTIVE/启用、3 EXPIRED。首轮三个失败账号随后被既有恢复流程重新标记 ACTIVE；这不能证明该时点真实生成可用，Arena 认证链路仍需处理。0.26.1 的完整 35/35 工具闭环为此前独立记录，不替代本轮结果。

证据：`backend/build/strict-v0262-suite.json`、`strict-v0262-*.json`、`strict-v0262-arena-retry1.json`、`strict-v0262-arena-account-status.json`。首轮和复测失败均保留。

### 当前部署的真实 Codex 工具闭环

Codex CLI 0.160.0、MiMo `mimo-v2.6-flash`、现有分发 Key、read-only sandbox，关闭 apps/plugins/multi_agent。读取既有合成图片，实测 `view_image → function_call_output(input_image) → 最终正确数字` 通过。临时本地 HTTP 转发器只记录方法、状态、工具名和内容类型，不保存 Key、请求正文或图片数据，结束时已关闭。

首次闭环两次 `/v1/responses` 均为 HTTP 200，记录到 `view_image` 和一次带 `input_image` 的工具结果；最终正确读出图片数字。第二次生成内部发生一次 `tool_call_generation_failed`，换账号尝试成功，未伪造工具成功。CLI 未显式启用 strict，严格模式的独立证据为上述 SDK/JUnit 验收。

CLI JSON 的 error item 为 `Model metadata ... not found ... fallback metadata`；为确认此条提示而作一次诊断补查，图片语义和工具回传仍通过。补查记录四次 HTTP 200，其中两次后端在生成前失败后由客户端重试，详情见下节；不能把 HTTP 200 或最终完成解释为所有中间尝试成功。Shell/patch 策略、model metadata fallback 仍为客户端边界，未绕过。

证据：`backend/build/codex-v0262-image-live.json`、`codex-v0262-image-live-diagnostics.json`。结束后 Argo Synced/Healthy、四 Pod Ready/restart=0，七个所选模型 guard `concurrent=0 / queue_depth=0 / circuit_state=CLOSED`，见 `release-0.26.2-final-runtime.json`。

### 新发现的 Redis 超时与 Read 性能边界

Codex 诊断补查期间，MiMo 两次生成前失败：`a564276b-e3e8-4830-a7e4-66e26b20eb98` 3109ms、`7cda5ba3-490c-4ad1-8055-40aa0d0461ae` 3057ms，均为 `QueryTimeoutException`、account_id=null、account_acquire_ms=0。同一窗口 API Key/catalog L2 读写/失效均出现 QueryTimeoutException，模型探针日志明确记录底层 `RedisCommandTimeoutException`；Redis 配置超时为 3s。客户端重试后完成，不将此现象记为性能问题已解决。

只读排查未发现 PostgreSQL 当前等待或阻塞；accounts 有 eligible 索引，约 388 个存活行。Redis 当前持久化状态正常，累计 slowlog 最慢命令约 28.93ms，RDB 保存正常；收尾资源快照 Server 3m CPU/727Mi、Redis 5m/6Mi，Server limit 为 1 CPU/1536Mi。这些是排查时点数据，不能排除故障窗口的网络、回调线程或瞬时资源问题。

已定位 Redis 超时窗口，具体触发链路尚未确定，未盲目放宽 timeout、改 PostgreSQL 索引、重启服务或迁移 Redis。后续优先采集故障窗口的 Server→Redis 往返/连接与响应回调耗时，再评估缓存与关键租约/guard 的隔离。全量模型目录和普通 Read 本轮没有新的分位数基准。

## 保留边界与回滚

严格函数参数检查不等于原生受限解码，也不能保证上游生成成功；pattern/format、循环/外部引用和其他未支持关键词明确拒绝。Responses 默认 `store:false`、strict 缺省非严格行为与 OpenAI 默认值仍有差异。WebSocket、background、Conversations、compact、hosted tools 不在本轮范围。Qwen 账号、公网 Cloudflare 502/524 和 LongCat 原生纯色识别问题仍按既有记录保留。

显式 strict 需要等完整工具参数校验完成再释放工具事件，增加工具参数的缓冲等待；普通文本/推理和非严格工具路径保持原有行为。本轮未重新进行 Read 性能基准，全量目录缓存、WAN/公网成本、厂商 WEB 推理时间仍需单独优化。Cloudflare Worker 仍未发布；当前入口的编码斜线同源 307 归一化保留，客户端应交给 SDK 编码并跟随重定向，避免手动重复编码。

回滚为 0.25.7 四组件不可变镜像，后缀 `20261003-v0.25.7-release-378475fe2fe201d53e4f8da6408cecf1909df346`。无需恢复数据库或 Key；回滚后模型详情/严格函数能力撤回，原有普通调用继续按旧契约运行。

若仅需撤回 0.26.2 默认值修复，可恢复已保留的 0.26.1 四镜像，后缀 `20261003-v0.26.1-release-74028a7bb212105e4a3d7c89832e11b1184b6e54`；模型详情和显式 schema strict 仍在，但无参数 strict 默认值误拒绝会恢复。回滚不承诺解决 Arena 凭据或 Redis 链路超时。
