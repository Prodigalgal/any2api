# 当前任务：排除 Grok 的六家 Runtime 全链路闭环

## 目标

按 DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen 逐厂商完成 K8S Runtime 的真实推理、账号生命周期、模型发现、账号租约切换、已声明多模态和错误边界验收；Grok、Grok Web、Grok Console 暂不纳入范围，API Channel 后置。

## 已完成

- `0.13.14` 已通过 CI 并由 Argo CD 部署到 `any2api` 命名空间，GLM 视觉模型的图片非流式和 SSE 请求均已在 K8S 真实通过。
- 六家选定模型的非流式和 SSE 文本请求均真实返回成功。
- MiMo、MiniMax、Qwen、GLM 的图片输入流式请求均成功；LongCat 官方聊天页已确认支持图片或单个文档上传，`0.14.0` 已完成同源上传实现和契约测试，待 K8S 真实图片/文档请求验收；DeepSeek 仍保持 text-only。
- 最近一小时成功推理使用了 DeepSeek 3、GLM 4、LongCat 3、MiMo 9、MiniMax 1、Qwen 3 个不同账号；MiniMax 当前只有 1 个 `ACTIVE/enabled` 账号，切换验证等待第二个有效账号。
- 生命周期调度已限制为默认并发 2、单次最多 claim 8 个动作；当前滚动观测没有新的 `OOMKilled`。
- MiniMax 的通用 `daily_checkin` 语义和具体 Runtime 实现已保留，账号额度依赖每日打卡的规则不绕过。

## 当前推进

- 发布 `0.14.0`，在 K8S 真实验收 LongCat 图片、文档、流式和错误边界；成功后再更新能力矩阵。
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
