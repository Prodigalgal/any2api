# Web 桥接遗留事项处理记录

## 范围与版本

用户要求处理上一轮报告中 Qwen 以外的全部遗留事项。延续 OpenAI Chat/Responses → 厂家 Web 的边界，调用方执行工具。原生产基线为 0.25.2，0.25.3–0.25.5 实测后继续修正，当前候选为 **0.25.6**；本记录将补齐最终部署和七家实测证据。

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

## 已完成验证

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

待补齐最终 CI、镜像 digest、GitOps revision、Argo/Pod、七家真实 SDK、native 能力、断连/有限并发、公网错误及 Read 前后数据。

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
| GLM/glm-5.2 | 44.736 | 9/9 PASS；独立长历史缺陷由 0.25.6 修复复测 |
| Grok Web/grok-3 | 10.516 | 9/9 PASS |
| LongCat/longcat-flash | 5.861 | 9/9 PASS |
| MiMo/mimo-v2.6-flash | 5.147 | 9/9 PASS |
| MinMax/MiniMax-M3.1-Flash-Preview | 7.925 | 9/9 PASS |

LongCat 的 0.25.5 native 9 项也通过：46 条历史、reasoning/math、真实 search URL、用户和工具图片 OCR、TXT/PDF reference、两函数调用及全部结果回放。纯色识别错误仍能在厂家原生接口复现，改变尺寸的白框实验未加入生产。

## 回滚

0.25.3 无 schema 迁移。可回滚四组件至 0.25.2 不可变镜像，并移除 `server-database-affinity.yaml` 引用恢复原调度；namespace 明确化可以保留。图像兼容方案须作为后续独立候选记录，不覆盖已发布制品。
