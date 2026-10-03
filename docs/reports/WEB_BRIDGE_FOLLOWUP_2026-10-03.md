# Web 桥接遗留事项处理记录

## 范围与版本

用户要求处理上一轮报告中 Qwen 以外的全部遗留事项。延续 OpenAI Chat/Responses → 厂家 Web 的边界，调用方执行工具。原生产基线为 0.25.2，0.25.3–0.25.6 实测后继续修正，**0.25.7 已部署并完成改动涉及的真实复测**。七家所选模型的通用协议、原生能力和上游限制分别记录；不将一款模型的结果外推到全厂家。

## 0.25.3 变更清单

| 分类 | 模块 | 行为与兼容性 |
|---|---|---|
| 账号可靠性 | ArenaProvider / InferenceCoordinator / CanonicalResponseGuard | `credential_rejected` 最多尝试三个不同账号，排除所有已尝试账号；保留认证隔离、恢复与释放。流式和非流式请求都禁止在有效输出后重试或切换通道。 |
| 流式可靠性 | OpenAiResponseWriter | 快速预检失败仍返回 JSON 和真实 HTTP 状态；上游延迟时，3 秒后发送并刷新 SSE 注释，保留内部换号。延迟失败的 Responses 流补齐 `response.created` → `response.in_progress` → `response.failed`，断连取消上游。 |
| 错误模型 | ProviderFailureSignals / OpenAiResponseWriter | Java/Netty 的超时及厂家 408/504 映射为网关 504；其他厂家 5xx 维持 502；不把厂家凭据失效映射成调用方 Key 401。 |
| Grok 延迟 | grok_web_browser.py | 同域轻量会话页用于 fetch/WebSocket；首帧预算包含浏览器/账号准备；记录 runtime selection、session auth、socket open、conversation attach、first frame、completion 的耗时，阶段事件不泄露到公开流。 |
| 图片预检 | LongcatMediaValidation | PNG/JPEG 的类型、base64、尺寸和 10 MiB 限制在账号租用前校验；无效图片返回 400，原始图片像素和上传流程保留。 |
| Read 查询 | ApiKeyService / ApiKeyGrantStore / AccountRepository | Key 列表与详情使用不含 hash 的扁平 root projection，权限仍批量读取且相互隔离；overview 的两个账户 COUNT 合并为一次查询。 |
| Read 事务 | ProviderRuntimeService / ProviderRuntimeRuleService / RequestLogService / OperationLogService / RegistrationJobService / RegistrationScheduleService | 去除纯 JDBC 读取的多余只读事务，写事务保留；分页、历史修订上界和 JSON 字段保持。 |
| 目录成本 | ModelCatalogCache / ModelCapabilityContract | 当前 L1/L2 目录快照只解码一次并在后台执行，失效仍由原缓存控制；不删减响应字段。仅输出 reasoning 的厂家不再宣告可设置 effort 等级。 |
| 部署 | build-and-deploy.yml / deploy/gitops/server-database-affinity.yaml | 四组件镜像与调度补丁同一 GitOps commit；Server 优先与 PostgreSQL 同节点，保留调度回退；Oracle overlay 明确 namespace，避免手工渲染将 routes 放入 default。 |

## 0.25.3 本地验证

- Backend `test bootJar`：**420 tests，415 passed，5 skipped，0 failures/errors**；包含真实 PostgreSQL/Liquibase 的 Key projection、权限隔离、批量上界及纯读回归。
- Automation：**496 tests passed**；Grok 的准备超时、预算扣除、阶段隔离与 WebSocket 事件顺序已覆盖；ruff check/format 通过。
- Web：lint、build 通过；六处源码版本与 JAR `Implementation-Version` 为 **0.25.3**，最新迁移 tag 仍为 0.24.0，无新数据库迁移。
- 官方 OpenAI Python SDK **2.54.0** 对本地真实 HTTP 网关与 PostgreSQL fixture：**11 组通过**，包括 namespace/custom、存储/跨 Key 隔离、长历史、并行工具、Chat/Responses 502/504 JSON、延迟 SSE failed。fixture 仅位于测试代码，生产不提供故障注入入口。
- 从实际 workflow 提取 GitOps 更新脚本，在隔离 clone 中连续执行两次：四镜像 tag、调度补丁引用、namespace 保持一致且没有重复引用；Kustomize 输出三个 HTTPRoute 均属于 any2api。

## LongCat 视觉定位

所有诊断只使用本任务生成的图片和现有账号，凭据仅在进程内存使用。

1. 上传后从返回 URL 下载：PNG/JPEG 的 SHA-256、字节数与源文件完全相同；宽高、fileExt、fileKey 与 `multiModal` 路由核验通过。
2. 直接调用 LongCat 原生 Web API：橙色 PNG、JPEG、RGBA、不同尺寸、标准 UUID 文件身份仍会被误判为粉色；蓝色纯色图也误判，绿色正确。该失败可独立于 OpenAI 桥接复现。
3. 两张不同图形/OCR 图片，使用相同且不含答案的 prompt：红三角/蓝方形/6248、绿圆/橙三角/1937 均被正确识别，证明视觉内容实际进入了厂家模型。
4. 独立兼容实验给纯色图增加 4 像素白色边框，原有像素完整保留：橙、蓝、红三组识别正确。此方案改变上游图像尺寸，已向用户请求语义边界选择，尚未加入生产候选。

不能把“上传成功”当成视觉准确率通过，也不能覆盖上述失败记录。

## 部署与生产验收

### 0.25.3 首轮生产反馈

- Source `b54bc7c06476e7cd5ae92749011c39dd9f191f8f`；CI `37088713358` 成功；GitOps `8ee51edede82d266b5b579cc5f071f5649f5ae7e`；四 Pod Ready、restart=0，Argo Synced/Healthy，Automation 代码、已安装包与 API 均为 0.25.3。
- Server 调度到 PostgreSQL 节点后，集群管理 Read 中 overview 236.40 → 88.28ms，providers 160.88 → 90.38ms，api-keys 238.32 → 90.37ms（n=3）。但 Redis 留在另一节点，未压缩的目录 L2 读写发生 QueryTimeoutException，`/v1/models` 升到 2690.54ms，首轮不算最终性能验收。
- 发现 GitOps 原顺序 Server wave=0、Automation wave=1；新 Server 探针与旧 Worker 终止窗口重叠，DeepSeek/Grok/LongCat 等记下暂态 FAILED，公开推理随后被 model_unavailable 503 拦截。保留所有首次失败记录；待 Worker Ready 后使用现有 admin probe 重新实测，未直接改数据库为 READY。
- direct/public 无效图片均为 HTTP 400、application/json、相同 error code 和 267 字节正文；request_id 分别为 `6dd6aaf4-302b-46eb-80ed-f2b450eccc21` / `78298214-56c6-4db8-9549-14cbd3c82f88`，Key 删除 204。
- 备份并清理 default namespace 的三个无后端重复 HTTPRoute；三个 any2api 主路由 Accepted/ResolvedRefs 保持 True。备份 `backend/build/default-routes-rollback-v0253.json` 可恢复。

### 0.25.4 增量修正

- `ModelCatalogSnapshotCodec` 对大型目录快照进行 gzip/base64 压缩，保持全部 JSON 字段和既有 TTL，后台线程只解码当前快照一次；内部 namespace 升为 v4，隔离旧实例，公开响应不变。小快照保持原 JSON；损坏压缩和超过 32 MiB 的解压数据明确失败。
- GitOps 的 Server wave=2，等待两类 Automation wave=1 就绪后更新。PostgreSQL/PV 与 Redis 布局保持原状。
- SDK 验收新增 `--common`：覆盖 namespace/custom/普通 function SSE，同时独立记录模型不支持的可选 verbosity 等控制；不把 optional model controls 强加给不支持的模型。
- 本地五个目录缓存/压缩测试、bootJar、ruff check/format、版本契约通过；实际 workflow 渲染验证 Automation=1、Server=2。

### 0.25.5 增量修正

- `ModelProbeService`：账号级 credential/anti-bot 拒绝不覆盖整个模型的可用状态；本地准备/传输异常保留已有证据并向上报告。真实厂家 canonical 失败及明确超时仍保存 FAILED。避免部署窗口中的本地异常产生持久 model_unavailable；不直接改库为 READY。
- `ProviderRetryPolicy`：已声明 required 工具未生成且尚无有效输出时，最多三个不同账号重试。有效 reasoning/text/tool 输出后仍禁止重放，保留清理和失败遥测。
- LongCat：原生 TXT 上传成功但模型不能读取的失败已实测复现；在已有文件上传基础上把完整、严格 UTF-8 正文加入原生输入，原文件字节不变，保留 10 MiB 限制。未扩展厂家已有文件格式，不把 PDF/Office 解析能力推定为已验。
- Cloudflare：准备固定域名/路径的 Worker 转发候选，直接传递原生 status/headers/body stream，六项 Node 测试和 Wrangler dry-run 通过。Wrangler 未登录；按 AGENTS.md 外部 SaaS 写入规则已请求授权，当前未发布，不宣称公网 502/524 已解决。

最终 CI、镜像 digest、GitOps revision、Argo/Pod、真实 SDK、native、断连及 Read 证据见下文；首次失败记录保留，不以修复后的成功覆盖。

### 0.25.6 GLM 历史与 agent 诊断修正

- GLM：真实 46 条历史分别以 developer/user 开始时，0.25.5 均未读到首条 reference；补完整 native history tree 仍失败。官方 WEB runtime 使用包含相同原始历史的当前签名 prompt 后成功。`build_glm_command` 现在将多轮角色、文本、已有工具调用/结果放入有效 prompt，单轮保持原文字，typed 附件保持原有 file identity；不修改调用方对象，也不补预期答案。
- 更新跨厂家工具契约回归：除完整保留最后的工具结果，还要求 GLM 实际签名 prompt 包含早期 developer 指令和 assistant call id。
- Automation **502 passed**，ruff check/format 通过；Backend bootJar 和源码/JAR **0.25.6** 版本契约通过，最新迁移 tag 仍为 0.24.0。Web build 通过；Backend 生产源码未改，完整门禁在 CI 再验。
- `codex_agent_smoke.py` 保留超时/非零退出时的部分 stdout/stderr、CLI/model/deadline 信息和明确未通过状态，报告脱敏，保持默认 120 秒及原 sandbox，不绕过命令策略。
- 真实 installed Codex 对 MiMo 完成 `view_image → function_call_output(input_image) → 6248`。网关请求 `a057897d-3056-4037-8e03-4d6aea19fc7e` 输出 view_image，`809fd067-1fce-4564-8b03-1d8de8a21e61` 接收一个工具输出和其中的图片，两次均成功；CLI exit=0，预期数字只在本地产物/断言中，未写入 prompt。Key `ba9017f4-fb2a-4dcb-9710-8fa4e43e1033` 删除 204。此前 command 测试的本地 CreateProcess 被客户端自动策略拒绝，仍记录为失败，不算后端命令执行成功。
- Worker 已获用户授权但 OAuth 登录超时；后续讨论确认其仅为可选候选，当前未发布、未绑定路由。直接 HTTPS 能用于 agent，现有公网 SSE 已实测持续 136.631 秒后正常取消；公网长非流式 Cloudflare 524/502 格式问题继续单列。

### 0.25.5 七家 SDK 真实验收

官方 Python SDK 2.54.0，对 `any2api-direct.mnnu.eu.org` 的临时限定 Key、AUTO WEB 路径，七家均 **9/9 PASS**。每家覆盖 models、Responses 非流式/流式、存储/跨 Key 所有权、namespace、custom/result、Chat function、普通 function SSE/named choice。客户端 timeout=240s，首次失败记录未删除。下表是一次实际请求的首 text delta，不能当成 SLA。

| 模型 | 首 text delta(s) | 核心结果 |
|---|---:|---|
| Arena/Max | 18.789 | 9/9 PASS |
| DeepSeek/default | 70.805 | 9/9 PASS |
| GLM/glm-5.2 | 44.736 | 9/9 PASS；独立长历史缺陷分别由 0.25.6 / 0.25.7 修复并复测 |
| Grok Web/grok-3 | 10.516 | 9/9 PASS |
| LongCat/longcat-flash | 5.861 | 9/9 PASS |
| MiMo/mimo-v2.6-flash | 5.147 | 9/9 PASS |
| MinMax/MiniMax-M3.1-Flash-Preview | 7.925 | 9/9 PASS |

LongCat 的 0.25.5 native 9 项也通过：46 条历史、reasoning/math、真实 search URL、用户和工具图片 OCR、TXT/PDF reference、两函数调用及全部结果回放。纯色识别错误仍能在厂家原生接口复现，改变尺寸的白框实验未加入生产。

### Arena 恢复和换号生产证据

2026-10-03 04:35 UTC：Arena ACTIVE=124、DEGRADED=1。过去 24 小时 INFERENCE 涉及 54 个账号，其中 47 个有成功证据、6 个遇到 credential_rejected，二者在该窗口无交集；这是被调用账号样本，不能推定全部 125 个账号的实时凭据有效率。

限定测试 Key 的请求 `b3effdb8-90b9-4a27-88f5-1badc2f954bb`：attempt 1/2 分别对不同账号凭据拒绝，attempt 3 对第三个账号成功，三个耗时为 14.203/15.281/25.311s；九组 SDK 总验收成功。证明实际最多三账号换号路径生效，失败账号没有复用。单元回归另外锁定输出后禁止重放及释放/恢复。

同窗口 reauthenticate 成功 14 次、失败 1 次，成功平均 40.350s、最大 66.850s；keepalive 成功 417、失败 50、运行中 2。保留失败与运行中状态，不把操作失败掩盖为恢复完成。

### 0.25.6 生产结果与 0.25.7 缺陷修复

- 0.25.6 Source `3a69dd2390be19349ab23cc11e01b9f6daf48994`、CI `37096960484` 成功、GitOps `fdd107a09e781164300e699afe4d13bfe9804d71`、四组件 Ready/restart=0、Argo Synced/Healthy，两类 Worker 的源码/安装包/API 版本一致。GLM SDK 9/9 再次通过，native 两函数结果均成功，早期 developer reference 成功。
- user-first 46 条历史仍失败：Java `SmartContextWindowManager` 预先按默认 32 条裁剪，GLM 收到的是裁剪提示和尾部。0.25.7 默认与 disabled 保留完整历史，仅显式 auto 使用原裁剪策略，明确厂家消息上限仍在 disabled 时拒绝，token/request-size 校验保持。这与 [OpenAI Responses truncation 默认 disabled](https://developers.openai.com/api/reference/cli/resources/responses/methods/create) 的语义对齐。
- 0.25.6 native 验收：MiMo 的七项全部通过；Arena、DeepSeek、GLM 的所支持项通过，未支持项明确 400。MinMax 历史/reasoning/两函数及回放通过，但图片先被 UI 发送生成，重放同一 session 遇到 HTTP 409 `session_not_idle`，工具图片尝试也遇到 transport 502，保留失败记录。
- 0.25.7 MinMax：media capture 阻断 fetch/XHR 上的实际生成，结束与异常均恢复 hook，每次清空旧捕获；通过文件 buffer 保留全部图片，避免临时路径和只取第一张；最终 replay 使用完整 context 和所选模型，保留原生附件身份。相关日志仅记录路径、数量、字段名，不记录正文、附件内容或 URL query。
- 新增真实 JavaScript hook 行为、失败清理、多图片及完整 replay 回归。Backend **433 tests，428 passed，5 skipped，0 failures/errors**；Automation **506 passed**；ruff check/format 与源码/JAR 0.25.7 版本契约通过，无新 migration。

### 0.25.7 发布与运行态

- Source `378475fe2fe201d53e4f8da6408cecf1909df346`；[CI 37098598658](https://github.com/Prodigalgal/any2api/actions/runs/37098598658) 的 Backend、Automation、Web、版本门禁、四镜像构建及 GitOps 更新全部成功。既有 browser-runtime 基座未重新发布。
- GitOps `0f7ce4d1bc651387c41d7b7d0d80929c8fdab5d5`，Argo **Synced / Healthy**；四组件均 **1/1 Ready、restart=0**。两类 Automation 的源码、installed package、API 版本均为 **0.25.7**。
- 镜像仓库 `docker.io/speedproxy/any2api`，不可变 tag 为 `<component>-20261003-v0.25.7-release-378475fe2fe201d53e4f8da6408cecf1909df346`。Server 位于 PostgreSQL 节点，两类 Automation、Web、Redis 位于原主节点；未移动数据库/PV。

| 组件 / Pod | sha256 digest |
|---|---|
| server / `any2api-server-7b59d9b4c9-rgrp2` | `9da3d566e717c513106d36c95c8831defb2a7d140fe8f90e60cee447f897df11` |
| web / `any2api-web-7876884995-j5fm2` | `7cd5372805cdee217b403bb333692756faed627eac29724ef7bbd41d13fd150b` |
| automation / `any2api-automation-77b4596c8f-xqm6v` | `0098e91a20eefa2eb82c2edd304ab36aa151145d136dbdc6029e605fb16945ae` |
| automation-arena / `any2api-automation-arena-9644c6ff6-jnbbc` | `cdca792154f332ed95b30b859221330656f19137c4944731feb01a2d1e7010e8` |

三个 any2api 主 HTTPRoute 保留，default 中的三个失效副本已清理；Oracle overlay 的 namespace 修正防止重复出现。没有 Cloudflare Worker 发布或路由绑定写入。

### 七家长历史与 native 验收

0.25.7 对七家均提交 46 条历史：reference 只位于第一条 user 内容，最后仅询问早期 reference，不补答案、不复用历史测试结果。七家均 HTTP 200 / completed / 内容正确；GLM 额外验证 developer-first。全部临时 Key 删除 **204**。

| 所选模型 | 0.25.7 user-first 46 条 | 已声明的 native 实测 | 明确限制 |
|---|---|---|---|
| Arena/Max | PASS，18.713s | 0.25.6 search URL、用户/工具图片、两函数与全部结果 PASS | effort 控制 400；不将 ACTIVE 数量当成凭据有效率 |
| DeepSeek/default | PASS，161.256s | 0.25.6 reasoning/math、search URL、两函数与全部结果 PASS | 图片 400；长请求仍受厂家延迟影响 |
| GLM/glm-5.2 | PASS，49.441s；developer 49.329s | 0.25.6 reasoning/math、search URL、两函数与全部结果 PASS | 图片 400 |
| Grok Web/grok-3 | PASS，10.258s | 0.25.6 两函数及全部结果 PASS；默认数学结果正确 | effort/search/图片 400；本次未观察到原生 reasoning delta，不记 reasoning PASS |
| LongCat/longcat-flash | PASS，6.756s | 0.25.5 reasoning/math、search URL、用户/工具 OCR、TXT/PDF、两函数及全部结果 PASS | 厂家纯色误判仍可复现，白框未进入生产 |
| MiMo/mimo-v2.6-flash | PASS，8.076s | 0.25.6 reasoning/math、search URL、用户/工具 OCR、两函数及全部结果 PASS；真实 Codex 图片工具闭环 PASS | CLI command 本机策略拒绝单列，不算后端失败 |
| MinMax/MiniMax-M3.1-Flash-Preview | PASS，11.342s | 0.25.7 reasoning/math、用户/工具 OCR、两函数及全部结果 PASS | search 400 |

MinMax 本次原生支持的六项均通过，未支持的 search 为 `unsupported_parameter` 400：用户图片请求 `9322910a-64a1-4bb2-8179-473772fea6d1` 用时 52.934s，工具图片请求 `8a9592d6-c295-4344-9d87-211d96cc1a5c` 用时 47.748s，均识别 6248。两函数及结果回放请求 `803287fd-ef44-4e6a-8c5f-bc90607cff09` / `9cc042cf-0edb-4215-b09d-ce3139d50177`，alpha/beta 和两个原始结果全部保留。

Grok 的默认数学调用回答 8917，实际 WEB channel 为 ASSISTANT_RESPONSE / NOTETAKER，没有可转发的 reasoning channel。泛化测试因要求非空 reasoning 将其标为 false，保留原记录并说明原因；不能据此宣称 reasoning 已通过，也不能凭空判断桥接丢弃了上游不存在的内容。

### 0.25.7 有限推理并发

对每家所选模型同时提交一个 Chat 和一个 Responses 非流式请求，使用相同的厂家限定临时 Key、不同的合成计算题，全局最多两个请求。**七家 14/14 HTTP 200、内容正确、终态正确**，没有观察到两个请求答案串扰；七个临时 Key 删除 204。

| 厂家 | Responses / Chat 总耗时(s) | 成功 attempt 的服务端重叠(s) |
|---|---|---:|
| GLM | 48.450 / 47.811 | 46.754 |
| DeepSeek | 25.784 / 39.689 | 24.775 |
| Arena | 20.904 / 21.017 | 18.858 |
| LongCat | 7.687 / 8.147 | 3.934 |
| MiMo | 4.370 / 4.929 | 3.192 |
| Grok Web | 11.833 / 11.776 | 10.422 |
| MinMax | 9.143 / 8.334 | 6.940 |

只读取这 14 个自有 request_id 的 usage_events 元数据；每家两个不同账号、attempt=1、success=true、queue_ms=0。服务端重叠由 created_at 与 duration_ms 计算，确认请求确实并行处理。另回查本次长历史、MinMax native 与推理并发使用的 15 个临时 Key，数据库残留为 0。此结果覆盖有界并发隔离，不是持续压测或厂家最大容量证明；取消/释放另由公网取消实测与回归覆盖。

### Grok 阶段耗时

0.25.6 自有请求 `1e70fe15-8685-461f-a19b-752f380b1d51` 的累积 phase_ms：runtime selection=9300、session auth=9508、socket open=10503、conversation attach=10897、first frame=11596、completion=15810。数值从同一请求起点累计，不能相加。另一次结果回放首帧=12284ms、completion=15432ms。

0.25.1 SDK 样本首增量 126.62s；0.25.5 样本为 10.516s，0.25.7 长历史总耗时 10.258s。轻量同域 session 页降低了可控初始化成本，阶段日志可定位仍有约 9–10s 的 runtime selection；这些不同时间/账号的样本不构成冷启动 SLA 或保证所有请求均在十秒内完成。

### direct / public 错误口径

- 0.25.6 两个入口各六项全部通过：401 invalid_api_key、403 insufficient_scope、400 invalid_request_error、404 response_not_found、400 unsupported_parameter、503 account_unavailable，均为 OpenAI JSON、包含 request_id。测试 Key 删除 204；0.25.7 未修改入口或错误 writer。
- 真实 HTTP 网关 fixture 的官方 SDK 验收覆盖 429/502/504、Chat/Responses JSON 及延迟 `response.failed`，12 组通过。未在生产主动制造 429/504，不将受控测试当成生产原生超时证明。
- 0.25.4 public 已捕获 Cloudflare 502 `origin_bad_gateway` 和 120 秒级非流式 524，正文脱离 OpenAI 契约；0.25.5 换号减少上游早期失败，但没有证明 Cloudflare 错误页全部消失。Worker 候选保留、未发布，当前 agent 可使用现有 direct HTTPS 与已验证的 SSE。

### 取消与 Read 分项证据

- 公网 SSE 首输出前/后取消均通过：`dd5a2933-8933-40bd-bbd6-ffbe1b422685` 在 5.104s 关闭，`4d5256eb-bf02-4e4c-9898-8bee93785635` 在 136.631s 首增量后关闭；遥测分别记 downstream_cancelled，账号 Redis lease 均为 0，临时 Key 删除 204。现有 3s 首注释及 15s 心跳已让该长流通过 Cloudflare，不需要重复新增心跳。
- 同主节点调用方对 18 个 Read 的 0.25.2 → 0.25.5 对比：overview 236.40→85.84ms、accounts 239.88→95.77ms、api-keys 238.32→99.84ms。Server 移到 PostgreSQL 节点后，主节点调用方的基础 HTTP RTT 约 80ms，health/session 的增加属于节点距离；数据库读减少跨节点往返。
- 全量目录仍有回退：集群 `/v1/models` 50.23→149.54ms（基线 n=3、0.25.5 n=5），direct 531.20→703.89ms（两轮均 n=5，目录 wire 均约 109KB，不能归因于变大）；目录字段、权限和动态运行态全部保留。0.25.3 的 Redis 大快照 2690.54ms 回退已由 0.25.4 压缩修复，但不能称最终全量目录已更快。
- Server 本机 HTTP 验证为 200：全目录 gzip 46–133ms、约 109KB，identity 25–133ms、约 1.50MB；此测量包含 bash/date/wc 的进程成本，只用于与网络开销分离，不能作为精确方法级 profile。两个 runtimeGuard 查询只是内存 map 查找，不据此增加缓存层。
- 0.25.5 四并发、28 次 Read 全部 200，elapsed=1.255s（基线 1.772s），Hikari pending=0；全目录 load p50=333.38ms、max=368.71ms。0.25.6 同方式 28 次全部 200、elapsed=1.231s、pending=0，目录 load p50=385.59ms、max=522.24ms。

0.25.6 同调用节点、n=5：overview=86.30ms、accounts=99.25ms、api-keys=104.91ms、health=83.10ms、全目录=178.36ms；普通管理接口改善保持，但全目录并未回到 0.25.2。0.25.7 没有继续修改 Read 查询或目录实现。

0.25.7 direct 五组交错采样，每组依次读取 health、MiMo 目录、全目录，15 次均 200：

| 接口 | response headers p50(ms) | body p50(ms) | total p50(ms) | decoded / wire |
|---|---:|---:|---:|---|
| `/healthz` | 396.56 | 0.10 | 396.67 | 15B / 15B |
| `/mimo/v1/models` | 622.25 | 0.34 | 622.59 | 81,541B / 5,301B |
| `/v1/models` | 574.90 | 81.09 | 724.54 | 1,511,612B / 109,304B |

各列独立取中位数，不能把 headers/body 的中位数相加当成 total 中位数。全目录单次 total 范围 461.77–1001.99ms；存在 headers 401.12ms、body 323.41ms 的样本。与本机 profile 联合看，响应序列化、跨节点目录缓存和公网传输都有成本，现有样本不支持把所有延迟归因于 SQL、CPU 或连接池排队。

目前保留全字段与动态运行态，不新增未经测量的缓存层。限定模型的 Key 或已有 `/{provider}/v1/models` 可以减少目录数据；全量目录和公网网络成本仍是后续具体性能项，不能写为“所有 Read 已解决”。

## 尚未完全闭环的事项

1. Arena：有限换号和恢复已实现且实测成功，厂家凭据仍会失效；24 小时样本不能证明整个账号池有效，既有 LOGIN_GATE 与 keepalive 失败继续保留。
2. LongCat：真实媒体桥接、非纯色 OCR、TXT/PDF 已通过；厂家原生纯色误判未解决。白框实验会改变输入尺寸，尚无用户选择，生产保持原图。
3. Grok：可控初始化优化及阶段观测完成；runtime selection 仍耗时，所选 grok-3 的 native reasoning 未观察到，不承诺全模型 reasoning/固定延迟。
4. Read：普通管理查询和有界负载已改善；全量目录对比基线仍回退，保留跨节点/响应体/公网成本实测和进一步优化空间。
5. public：常见 JSON 错误和长 SSE/取消通过；Cloudflare 生成的 502/524 正文仍有入口限制。Worker 为可选未发布候选，非后端厂家桥接的前置条件。
6. Codex：真实只读图片工具闭环已通过；本地 command 策略拒绝和未知第三方模型 metadata 警告属于客户端配置，未绕过策略。七家结果仅覆盖所选模型，不扩展到所有模型/所有协议；Qwen 按用户要求不处理。

## 回滚

0.25.3–0.25.7 无 schema 迁移，最新 migration tag 保持 0.24.0。

- 0.25.7 紧急应用回滚：GitOps 将四组件恢复到 `20261003-v0.25.6-release-3a69dd2390be19349ab23cc11e01b9f6daf48994` 不可变镜像，保留当前 namespace、Automation wave=1 / Server wave=2 和数据库亲和补丁。会重新引入默认 user 历史裁剪及 MinMax 图片重复生成问题，回滚后须限制受影响调用并复测。
- 回退本轮全部修复：恢复 0.25.2 四组件镜像，后缀 `20261002-v0.25.2-release-129dbf9b7b3be71936384d36796d639f81a1a9df`，并移除 `server-database-affinity.yaml` 引用恢复原调度；namespace 明确化可以保留。会撤回换号、错误、目录压缩和 Read 修复，不作为首选恢复策略。
- 不覆盖镜像 tag、不移动 PostgreSQL/PV、不回收候选版本；default route 备份仅在确实需要恢复旧路由时使用。后续图片兼容或边缘发布须另记可回滚候选，当前没有边缘变更可回滚。

## 原始证据

原始 JSON/日志位于 Git 忽略的 `backend/build`，测试内容均为本任务生成，报告不含凭据：

- 最终发布：`ci-0.25.7.json`、`release-0.25.7-runtime.json` / `.log`、`release-0.25.7-final-state.json`、`followup-v0257-backend-summary.json`、`followup-v0257-backend-full.log`、`followup-v0257-automation.log`。
- 七家 SDK：`vendor-suite-v0255-direct.json`、`live-v0255-direct-{provider}-sdk-auto-240s-common.json`、`live-v0255-direct-{provider}-full.log`；GLM 复验 `live-v0256-direct-glm-sdk-auto-240s-common.json`、`live-v0256-direct-glm-full.log`。
- native 与历史：`native-v0255-longcat.json`、`native-v0256-{provider}.json`、`native-v0257-minmax.json` / `.log`、`history-suite-v0257.json`、`history-v0257-{provider}.json` / `.log`；旧失败结果保持原 label。
- 有限推理并发：`inference-concurrency-v0257.json` / `.log`，包含 14 个自有请求及对应元数据，不包含凭据或其他用户正文。
- agent/错误/取消：`live-v0255-mimo-codex-image.json` / `.log`、`followup-v0256-codex-fixture.json`、`followup-v0256-codex-timeout.json`、`gateway-errors-v0256.json` / `errors-v0256.log`、`cancellation-v0255-public.json` / `.log`。
- 账号/性能：`arena-pool-v0256-predeploy.log`、`read-v0252-followup-before-*.json*`、`read-v0255-followup-after-*.json*`、`read-v0256-followup-after-*.json*`、`models-local-v0255.log`、`models-direct-v0257.json` / `.log`。

花括号及星号表示独立文件的命名模式；不代表所有 provider 都在同一版本重复全矩阵。最终 docs/tasks 证据提交由 workflow paths-ignore 排除，已验收的 0.25.7 制品不重建、不覆盖。
