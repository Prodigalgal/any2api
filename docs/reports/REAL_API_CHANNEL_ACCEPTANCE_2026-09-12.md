# API Channel K8S 真实验收记录（2026-09-12）

## 范围与制品

本轮只验证 Grok 之外的 Arena、DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen；
请求从 K8S `any2api-e2e-20260817` 的 Automation Pod 发起，经集群内
`http://any2api-server:8080` Service 进入统一 API 网关。没有执行账号注册、删除、重置、
凭据导入或生产 namespace 写入。

| 项目 | 证据 |
| --- | --- |
| 源码/业务版本 | `0.17.2`，提交 `92458bb5e86a925f685bdecce53bfba45e46d9b3` |
| GitHub Actions | `34686189354`：`backend-quality`、`automation-quality`、`web-quality` 和三个镜像构建均成功 |
| K8S namespace | `any2api-e2e-20260817`，context `kubernetes-admin@sg-osaka-dualstack` |
| 当前 server/automation 镜像 | `server-sha-92458bb5e86a925f685bdecce53bfba45e46d9b3` / `automation-sha-92458bb5e86a925f685bdecce53bfba45e46d9b3` |
| 模式配置 | `deepseek/glm/longcat/mimo/qwen=API`；`arena/minmax=DEFAULT`（按默认 Runtime） |

模式配置来自 E2E PostgreSQL `providers.config.inference_transport_mode`；本轮请求走统一
`/internal/v1/providers/{provider}/actions/*` Action 边界。当前版本的 inference 日志尚未把
channel 单独打印出来，后续版本会补上 `channel=api`，避免仅依赖配置快照判读。

## API 真实请求结果

请求均使用最小文本或测试媒体，结果只记录状态、帧结构和脱敏后的请求 ID。

| Provider / 输入 | 形态 | 结果 | 结论 |
| --- | --- | --- | --- |
| DeepSeek `default` 文本 | 非流式 | HTTP 200，1 个 choice，输出长度 2；约 52.5s；`145a9cd2-...` | 通过 |
| DeepSeek `default` 文本 | SSE | HTTP 200，13 帧、3 个 JSON 数据帧、包含 `[DONE]`；约 89.0s；`2513add1-...` | 通过，延迟偏高 |
| LongCat `longcat-pro` 文本 | 非流式 | HTTP 200，1 个 choice；约 23.0s；`f2f0cace-...` | 通过 |
| LongCat `longcat-pro` 文本 | SSE | HTTP 200，25 帧、23 个 JSON 数据帧、包含 `[DONE]`；约 7.2s；`c7010055-...` | 通过 |
| LongCat `longcat-pro` 图片 | 非流式 | 32×32 PNG，HTTP 200，1 个 choice；约 8.4s；`666dcb5d-...` | 图片通过 |
| LongCat `longcat-pro` PDF | 非流式 | 上传、会话和 completion 均 HTTP 200；`180b0b67-...`，但模型返回无法读取 PDF，未识别测试文档内容 | 文件上传通过，文档读取未通过 |
| MiMo `mimo-v2.5` 文本 | 非流式 | HTTP 200，1 个 choice；约 5.4s；`1fcb4bd3-...` | 通过 |
| MiMo `mimo-v2.5` 文本 | SSE | HTTP 200，19 帧、17 个 JSON 数据帧、包含 `[DONE]`；约 7.3s；`6efec89d-...` | 通过 |
| MiMo `mimo-v2.5` 图片 | 非流式 | 32×32 PNG，HTTP 200，1 个 choice；约 16.1s；`8f440f71-...` | 图片通过 |
| GLM `0727-106B-API` 文本 | 非流式 | HTTP 502，`anti_bot_rejected`；约 3.4s；`0457ddb9-...` | 真实厂商验证边界，未通过 |
| GLM `0727-106B-API` 文本 | SSE | HTTP 200 SSE 错误帧 `account_unavailable`；前一请求触发短暂风控冷却；`3939e8b6-...` | 未形成成功 completion |

GLM 的失败被统一归类为 `anti_bot_rejected`，不降级为 `credential_rejected`，也没有生成、
伪造或绕过验证码/风控票据。显式 API 继续失败关闭；只有 `AUTO` 才允许按既有策略考虑
Runtime。

## 账号与运行态

E2E 当前账号聚合快照为：DeepSeek 1 个 ACTIVE/enabled、GLM 1 个 enabled（本轮后进入短暂
风控冷却）、LongCat 1 个 ACTIVE/enabled、MiMo 1 个 ACTIVE/enabled、MiniMax 0 个可用账号、
Qwen 0 个可用账号；Arena 也没有可租约账号。因此 Arena、MiniMax、Qwen 的 API completion
本轮没有伪造为失败或通过，必须在拥有可用账号后再做真实验收。

四个业务 Pod 均 `Ready=true`、重启数为 0；本轮观测 Automation 约 652Mi、Server 约 441Mi，
没有新增 `OOMKilled`/`OOMKilling`。历史 readiness 超时事件仍保留，不能作为当前 Pod 故障。

## 0.17.3 运行态收口

为让后续验收能够直接区分 API 与 Runtime，提交 `70926efca11ca1a1c3be8543785834e41a745d48`
将最终 `ProviderTransportMode` 写入 inference start/finish 日志，并加入耗时指标的
`channel` 标签。GitHub Actions `34689733061` 的 Backend、Automation、WEB 质量检查和三套
镜像构建均成功。

该版本已只更新到 E2E：server/automation 镜像均为 `*-sha-70926efca11ca1a1c3be8543785834e41a745d48`，
四个业务 Pod 当前 `Ready=true`。滚动初始阶段 server 曾因 PostgreSQL Service DNS 瞬态不可解析退出
一次（`UnknownHostException: any2api-postgres`，退出码 1），重启后正常启动；后续从 Automation
连续检查 `healthz` 和 `readyz` 均 HTTP 200。当前 Server 重启计数为 1，但没有 `OOMKilled` 或
`OOMKilling`，该次重启不能归因于内存不足。

使用新版本发起一条 MiMo `mimo-v2.5` API 文本请求，结果为 HTTP 200、1 个 choice，
请求 ID `f50e41ca-...`；Server 日志同时记录 `inference_started ... channel=api` 和
`inference_finished ... channel=api ... status=SUCCEEDED`。生产 namespace 未更新，仍保持上一条
不可变发布链路。

## 当前 API 门禁结论

- 已取得 API 文本非流式/SSE 双形态真实成功证据：DeepSeek、LongCat、MiMo。
- 已取得 API 图片非流式真实成功证据：LongCat、MiMo；图片 SSE 和多账号覆盖仍需补齐。
- LongCat PDF 目前只能确认上传链路，不能确认 API 文档内容读取；继续保持未 Ready。
- GLM API 被真实厂商 anti-bot 阻断；需要厂商签发的验证上下文或使用 `AUTO` 的 Runtime 回退，
  不做 API 风控绕过。
- Arena、MiniMax、Qwen 因当前没有可用账号，保持未验收状态。代码存在、目录存在或 CI 通过，
  都不等于这些 Provider 的 API 运行态 Ready。

## 下一批工作

1. LongCat 复用已验证的真实 PDF 文档重新做内容读取验收，并补图片 SSE。
2. MiMo 补图片 SSE 和第二账号覆盖；DeepSeek 继续观察长首字节延迟。
3. 为 Arena、MiniMax、Qwen 准备可用账号后，逐家做 API 模型发现、文本非流式/SSE 验收；
   没有账号时不通过请求重试制造噪声。
