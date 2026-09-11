# 当前任务：排除 Grok 的六家 Runtime 全链路闭环

## 目标

按 DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen 逐厂商完成 K8S Runtime 的真实推理、账号生命周期、模型发现、账号租约切换、已声明多模态和错误边界验收；Grok、Grok Web、Grok Console 暂不纳入范围，API Channel 后置。

## 已完成

- `9944909` 已通过 CI `34476956811`，源码镜像通过 GitOps `ac420b0` 部署；随后配额修复 GitOps `9642473` 已由 Argo CD 应用。
- `5b4cdb5` 已通过 CI `34527813416`；GitOps `45bbb52` 已切换到 server、automation、web 的 `*-sha-5b4cdb5...` 不可变镜像，Argo CD revision 为 `45bbb527...`，三套 Pod 当前 Ready 且零重启。
- GLM 视觉模型的图片非流式和 SSE 请求、文本非流式和 SSE 请求均已在 K8S 真实通过；模型目录仍为 `probe_status=READY`，但健康窗口状态为 `DEGRADED`，暂不标稳定 Ready。
- 六家选定模型的非流式和 SSE 文本请求均真实返回成功。
- MiMo、MiniMax、GLM 的图片输入流式请求均成功；LongCat 已完成同源上传实现和契约测试，并在 K8S 真实通过图片非流式/SSE、有效 PDF 非流式/SSE、有效 DOCX 非流式请求；DeepSeek 仍保持 text-only；Qwen 最新图片复测仍未得到可用输出，不能标记为图片 Ready。
- LongCat 的 TXT 上传可以成功但当前上游不返回文件内容解析，其他未实测扩展不计入 Ready；因此 LongCat 当前是“图片 + PDF/DOCX 已通过，文件扩展全量仍观察中”。
- 当前账号池为 DeepSeek 16、GLM 23、LongCat 26、MiMo 57、MiniMax 7、Qwen 47 个 `ACTIVE/enabled` 账号；Qwen 和 MiniMax 均没有当前 `quota_limited` 账号，不能把 Qwen 图片失败直接归因于额度耗尽。
- 生命周期调度已限制为默认并发 2、单次最多 claim 8 个动作；当前滚动观测没有新的 `OOMKilled`。namespace quota 已从 `requests.memory=6Gi/limits.memory=16Gi` 调整为 `8Gi/20Gi`，为滚动副本留出余量。
- MiniMax 的通用 `daily_checkin` 语义和具体 Runtime 实现已保留，账号额度依赖每日打卡的规则不绕过。
- LongCat 在当前发布上补充完成 TXT 非流式/SSE 真实 completion；两个不同 ACTIVE/enabled 账号的 `longcat-pro` 账号探针均通过，最近 30 分钟成功请求使用 9 个不同账号。`longcat-flash` 在真实成功样本补充后恢复为 `READY`。
- Runtime 的公共 Action 已统一先经过共享 semantic command 校验，再由 DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen 各自的 Runtime mapper 翻译为厂商字段；`rawRequest` 不进入 Runtime/API Action 边界。API Channel 仍按计划后置。
- `ProviderActionDispatcher` 现在会在解析 `CHAT` binding 前统一校验 semantic command；厂商 mapper 保留防御性校验并只负责自己的字段、嵌套、上传和事件协议转换。六家 Runtime 的具体映射矩阵已补入 `docs/architecture/API_CONTRACTS.md`。
- `a99ec65` 已通过 CI `34537508021` 并由 GitOps `d564532` 发布；Argo CD 为 `Synced / Healthy / Succeeded`，三套 Deployment 已运行同一不可变镜像，Qwen 图片真实复测确认 `image_count=1`、上传 `file_count=1`，上游 `invalid_input` 被映射为 HTTP 400 `invalid_request_error`，且服务端仅 `attempt=1`。

## 当前推进

- LongCat 的图片、PDF、DOCX、TXT 已取得真实 completion；26 个账号均为 `ACTIVE/enabled`，自然 `keepalive` 事件已取得 `SUCCEEDED / lifecycle_completed` 证据（约 21.5s），继续观察多账号覆盖、24 小时健康窗口和其他未逐项验证的文件扩展，不将其自动扩大为全部文件格式 Ready。
- Qwen 文本探针、keepalive 和 32x32 图片 completion 已在 `ce2ee60` 发布上成功；1x1 图片仍返回 HTTP 200、392 字节、仅含 `error` 的不完整 SSE，公共层转换为不可重试的 HTTP 400 `invalid_request_error`，继续补齐尺寸边界和稳定性证据，Qwen 整体保持未 Ready。
- 等待 LongCat 自然 keepalive 和 MiniMax 自然 `daily_checkin` 到期执行，并记录真实结果。
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
- 新版本在 K8S Pod Ready、零重启、无新增 OOMKilled，浏览器进程预算和生命周期并发限制持续生效。
- 代码、测试、GitOps、镜像和运行态版本保持同一不可变发布链路。
