# 当前任务：Arena 接入与 Grok 之外六家 Runtime 全链路闭环

## Arena 适配（0.15.0，未 Ready）

- 已新增 `arena` Automation Provider 与 Java `ArenaProvider`，仅启用 `camoufox_browser_runtime`，不注册官方 API/CLI Channel。
- 注册使用共享 `TempMailClient` 的 email magic link：每个 `register` 操作只创建一个临时邮箱，先快照历史邮件 ID，再提交 `/nextjs-api/sign-up/magic-link`，验证新链接并通过 `/api/me` 核对身份；Manifest 与 Java 调度器均限制 `target=1`、`maxAttempts=1`。
- 模型发现从登录后的 `/text/direct?model_a=max` 页面 RSC `initialModels` 提取 UUID、显示名、输入/输出能力；Arena UUID 只由当前页面目录解析，不把显示名直接当作上游 ID。
- 文本请求使用 Arena `POST /nextjs-api/stream/create-evaluation` 的一字符前缀 NDJSON 帧；`0/2/3/d/e/f/g` 已映射到统一 canonical events，验证码、额度、认证、限流、模型不可用和协议错误分别归类。
- 图片/PDF 输入使用同一账号页面导出的 `uploadFile` 与 signed upload action，随后发送 `experimental_attachments`；当前适配只声明 PNG/JPEG/WebP 与 PDF，远程 URL、file ID、音频、视频和未声明文档格式在浏览器请求前失败。
- `provider_options.arena.web_search=true`（或契约允许的 `web_search`）映射为 Arena 原生 `modality="search"`；Search 能力按当前模型目录 `outputCapabilities.search` 做检查。
- 当前仅完成代码、单元/契约测试和协议审计；未执行真实外部注册、邮件投递、账号登录或 Arena inference probe，因此不得标记 `Ready`。CI/GitOps/K8S 与真实账号验收仍是后续发布门槛。

## 目标

按 DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen 逐厂商完成 K8S Runtime 的真实推理、账号生命周期、模型发现、账号租约切换、已声明多模态和错误边界验收；Grok、Grok Web、Grok Console 暂不纳入范围，API Channel 后置。

## 已完成

- `9944909` 已通过 CI `34476956811`，源码镜像通过 GitOps `ac420b0` 部署；随后配额修复 GitOps `9642473` 已由 Argo CD 应用。
- `5b4cdb5` 已通过 CI `34527813416`；GitOps `45bbb52` 已切换到 server、automation、web 的 `*-sha-5b4cdb5...` 不可变镜像，Argo CD revision 为 `45bbb527...`，三套 Pod 当前 Ready 且零重启。
- GLM-4.6V 图片 SSE 连续三次在 K8S 真实通过，当前 `rolling_success_rate=90.48%`、`p95=53.7s`，已达到该模型 Ready 门槛；`glm-5.2` 仍为 Ready。`glm-4.7`/`glm-5.3` 仍因窗口指标为 `DEGRADED`，另有多个目录模型为真实探针判定的 `UNAVAILABLE/provider_upstream_error`，不能把 GLM 全目录标为 Ready。
- 六家选定模型的非流式和 SSE 文本请求均真实返回成功。
- MiMo、MiniMax、GLM 的图片输入流式请求均成功；LongCat 已完成同源上传实现和契约测试，并在 K8S 真实通过图片非流式/SSE、有效 PDF 非流式/SSE、有效 DOCX/TXT 请求；DeepSeek 仍保持 text-only；Qwen 32x32 图片非流式/SSE 均已成功，但 1x1 仍为明确 `invalid_request_error`，不能标记为完整图片 Ready。
- LongCat 的 TXT 上传可以成功但当前上游不返回文件内容解析，其他未实测扩展不计入 Ready；因此 LongCat 当前是“图片 + PDF/DOCX 已通过，文件扩展全量仍观察中”。
- 当前账号表为 DeepSeek 16、GLM 23、LongCat 26、MiMo 57、MiniMax 7、Qwen 47 个 `ACTIVE/enabled`；Qwen 其中 44 个 `expires_at` 已过期，模型目录实际仅 3 个 eligible/available，MiniMax 当前 7 个 eligible/available，二者均无当前 `quota_limited` 账号。Qwen 的剩余阻断是凭据生命周期和健康窗口，不直接归因于额度耗尽。
- 生命周期调度已限制为默认并发 2、单次最多 claim 8 个动作；当前滚动观测没有新的 `OOMKilled`。namespace quota 已从 `requests.memory=6Gi/limits.memory=16Gi` 调整为 `8Gi/20Gi`，为滚动副本留出余量。
- MiniMax 的通用 `daily_checkin` 语义和具体 Runtime 实现已保留，账号额度依赖每日打卡的规则不绕过。
- LongCat 在当前发布上补充完成 TXT 非流式/SSE 真实 completion；两个不同 ACTIVE/enabled 账号的 `longcat-pro` 账号探针均通过，最近 30 分钟成功请求使用 9 个不同账号。`longcat-flash` 在真实成功样本补充后恢复为 `READY`。
- Runtime 的公共 Action 已统一先经过共享 semantic command 校验，再由 DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen 各自的 Runtime mapper 翻译为厂商字段；`rawRequest` 不进入 Runtime/API Action 边界。API Channel 仍按计划后置。
- `ProviderActionDispatcher` 现在会在解析 `CHAT` binding 前统一校验 semantic command；厂商 mapper 保留防御性校验并只负责自己的字段、嵌套、上传和事件协议转换。六家 Runtime 的具体映射矩阵已补入 `docs/architecture/API_CONTRACTS.md`。
- `a99ec65` 已通过 CI `34537508021` 并由 GitOps `d564532` 发布；Argo CD 为 `Synced / Healthy / Succeeded`，三套 Deployment 已运行同一不可变镜像，Qwen 图片真实复测确认 `image_count=1`、上传 `file_count=1`，上游 `invalid_input` 被映射为 HTTP 400 `invalid_request_error`，且服务端仅 `attempt=1`。

## 当前推进

- 最新业务版本已迭代到 `0.14.1`：源码 `fc33232`、CI `34556906088`、GitOps `46b6e98`、Argo revision `46b6e985...` 已形成同一不可变发布链路；server、automation、web 全部运行 `*-sha-fc33232...`，Pod Ready、重启 0、无新的 `OOMKilling` 事件。本地 `ircs-prod-config` checkout 已 fast-forward 到 `46b6e98`。
- 生命周期过期凭据修复已在生产生效：Qwen 过期 keepalive 记录为 `credential_refresh_scheduled`，账号进入 `EXPIRED/disabled` 并生成 `reauthenticate`；截至 11:27 已有 7 笔真实 reauthenticate 在 `inference_probe` 阶段因 `provider_transport_error/PrematureCloseException` 失败，另有 1 笔 keepalive 同类失败，当前继续处理上游 Runtime 稳定性，不标 Qwen Ready。:codex-annotation{index="1"}
- Qwen 当前账号快照为 6 个 `ACTIVE/enabled`（其中 3 个 expiry fence 已过期）、40 个 `EXPIRED/disabled`、1 个 `PENDING/disabled`；这证明生命周期分流已发生，但恢复稳定性和上游 transport 仍未闭环。
- MiniMax 保留 7 个 `daily_checkin` 待执行动作，最早中国时间 15:02 到期；继续等待自然打卡结果，不提前伪造额度或完成事件。
- Automation 当前约 `1361Mi`、内存 limit `6Gi`，浏览器预算日志显示等待/idle eviction 正常工作；本次发布后无 Pod 重启和 OOM 事件。

- LongCat 的图片、PDF、DOCX、TXT 已取得真实 completion；26 个账号均为 `ACTIVE/enabled`，自然 `keepalive` 事件已取得 `SUCCEEDED / lifecycle_completed` 证据；当前生产制品 `ce2ee60` 在 K8S 内新增文本请求返回 HTTP 200，服务端 `attempt=1/status=SUCCEEDED`，五个目录模型均 `available=true/probe=READY/circuit=CLOSED`，滚动成功率约 90.9%–100%，按本轮声明范围达到 Runtime Ready。继续观察多账号覆盖、24 小时健康窗口和其他未逐项验证的文件扩展，不扩大为全部文件格式 Ready。
- Qwen 文本探针、keepalive 和 32x32 图片非流式/SSE completion 已在 `ce2ee60` 发布上成功；本次 K8S SSE 请求 HTTP 200，39 个 SSE 帧、包含 `[DONE]`，服务端 `attempt=1/status=SUCCEEDED`。1x1 图片仍返回 HTTP 200、392 字节、仅含 `error` 的不完整 SSE，公共层转换为不可重试的 HTTP 400 `invalid_request_error`；Qwen 当前仍为 `DEGRADED`，继续补齐尺寸边界和稳定性证据，整体保持未 Ready。
- MiniMax-M3 在 K8S 内新增 SSE 和两次非流式请求均 `attempt=1/status=SUCCEEDED`，三个不同账号分别承载请求，证明租约切换；7 个账号均可用且 `quota_limited=0`。但 M3/M2.7 24 小时滚动成功率约 87.1%/31.4%，当前仍为 `DEGRADED`，且自然 `daily_checkin` 尚未取得完成事件，继续观察真实结果。
- DeepSeek 最新 SSE 请求 HTTP 200、`attempt=1/status=SUCCEEDED`，但首字节约 122.1s、P95 约 125.7s，`DEGRADED` 属于真实上游延迟风险；MiMo-v2.5 最新图片 SSE HTTP 200、`attempt=1/status=SUCCEEDED`，但滚动成功率约 85.7%，继续观察历史失败样本自然退出。
- 继续等待 LongCat 自然 keepalive 和 MiniMax 自然 `daily_checkin` 到期执行，并记录真实结果。
- 继续观察 24 小时 Runtime 健康窗口，区分历史失败与新版本失败；不通过清理历史数据或降低阈值伪造 Ready。
- 当前多模态范围只覆盖图片和文档；音频、视频不纳入本轮 LongCat 聊天输入能力。
- GLM 图片请求只允许官方目录标记 `capabilities.vision=true` 的模型，且只接受 inline base64 图片。

## 非目标

- 不接入厂商公开渠道 API，不绕过验证码、风控、额度和每日打卡限制。
- 不批量注册、启用、删除或重置 Grok 三通道账号。
- 不把尚未经过真实 Web/CLI 请求的能力标记为 Ready。

## 验收标准

- 每家选定 Provider 至少有模型发现、文本非流式、文本 SSE、账号保活和账号租约/切换的运行证据。
- 声明图片输入的 Provider 通过真实图片请求；未声明的 Provider 对图片输入给出明确契约错误。
- Arena 还必须分别取得真实文本、Search、图片和 PDF 请求的非流式/SSE 证据；注册成功必须再完成 Arena 账号探针后才能进入 inference pool。
- 新版本在 K8S Pod Ready、零重启、无新增 OOMKilled，浏览器进程预算和生命周期并发限制持续生效。
- 代码、测试、GitOps、镜像和运行态版本保持同一不可变发布链路。
