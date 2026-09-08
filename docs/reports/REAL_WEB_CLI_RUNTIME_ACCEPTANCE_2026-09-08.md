# Web API/CLI 反代真实验收记录（2026-09-08）

## 验收口径

本项目对外提供 OpenAI-compatible API，但本次验收的上游不是厂商公开渠道 API，而是各厂商 Web 产品或 CLI 工具实际使用的 Web API、页面 `fetch`、页面 WebSocket 和浏览器会话。Camoufox 只负责承载真实浏览器上下文、Cookie、页面状态、代理和上游请求，不通过模拟点击替代可观测的页面请求。

本记录不包含账号邮箱、账号 ID、Cookie、Token、代理地址、完整请求体或模型输出。

## 目标环境

| 项目 | 结果 |
|---|---|
| 线上入口 | `https://any2api.mnnu.eu.org`（Cloudflare） |
| 对照入口 | `https://any2api-direct.mnnu.eu.org`（DNS-only） |
| 当前线上制品 | `c090f17`；server、automation、web 三个镜像均为该提交 |
| 线上健康 | `/healthz`、`/readyz`、`/actuator/health` 均返回 HTTP 200 |
| 本地候选 | 当前工作树未提交，未部署；本次线上结果不代表本地候选已经 live 验收 |

## K8S 集群内真实验收

本轮以 K8S 运行态为准，不把本机 Camoufox 网络或浏览器结果作为通过条件。请求从当前运行中的 `any2api-automation-7897f7f6fc-gcb5g` Pod 发起，经集群内 `http://any2api-server:8080` Service 进入生产 `any2api-server` 和 Automation/Camoufox；使用现有 `ANY2API_PUBLIC_API_KEY`，只验证，不输出密钥，也没有执行账号激活、导入、删除或配置修改。

| 项目 | 结果 |
|---|---|
| K8S context | `kubernetes-admin@sg-osaka-dualstack` |
| namespace | `any2api` |
| Automation Pod | `any2api-automation-7897f7f6fc-gcb5g`，容器 `automation` |
| 集群内服务入口 | `http://any2api-server:8080` |
| 运行制品 | `c090f17`；尚不是当前工作树的未提交迁移 |
| 请求范围 | 5 个文本/流式请求、1 个图片请求；`max_tokens <= 8` |
| 关联证据 | `X-Request-ID=k8s-live-20260908-29f50ea02dc1-*` |

## K8S 集群内请求结果

| Provider/链路 | 请求 | 结果 | 运行态证据与诊断 |
|---|---|---|---|
| DeepSeek Web | 文本、非流式 | HTTP 502，`gateway_execution_error` | 集群内直连服务仍复现，排除本机网络和 Cloudflare 作为唯一原因；未形成 completion |
| GLM Web | 文本、非流式 | HTTP 200；choice/usage 存在，但内容为空 | server 日志记录 `GLM stream completed without answer deltas`，协议完成但业务 completion 不通过 |
| LongCat Web/CLI | 文本、非流式 | HTTP 502，`gateway_execution_error` | 集群内直连服务仍复现，未形成 completion |
| Qwen Web | 文本、非流式 | HTTP 200；choice/usage 存在，内容长度 3 | Camoufox 真实调用 `/api/v2/chats/new` 和 `/api/v2/chat/completions`，均返回 200 |
| Qwen Web | 文本、SSE | HTTP 200；4 个 `data:` 帧，收到 `[DONE]` | server telemetry 为 `SUCCEEDED`；真实流式 Web API 反代通过 |
| Qwen Web | 64×64 PNG 图片 | HTTP 502，`provider_transport_error` | `/api/v2/files/getstsToken` 返回 200，`/api/v2/chats/new` 返回 200；后续浏览器 fetch 返回 502，图片 completion 未通过 |

## 线上账号与模型前置条件

通过只读管理会话盘点得到 2663 个账号：

| Provider | 账号状态摘要 | 模型目录可用性 |
|---|---|---|
| DeepSeek | 16 个 `ACTIVE` | 3/3 可用 |
| GLM | 23 个 `ACTIVE` | 1/1 可用 |
| LongCat | 26 个 `ACTIVE` | 5/5 可用 |
| Qwen | 47 个 `ACTIVE` | 6/6 可用 |
| MiMo | 57 个 `ACTIVE` | 0 个模型可用 |
| MinMax | 30 个 `PENDING`、1 个 `DISABLED` | 0 个模型可用 |
| Grok Build | 820 个 `PENDING`、1 个 `EXPIRED` | 无可执行账号样本 |
| Grok Web | 821 个 `PENDING` | 无可执行账号样本 |
| Grok Console | 821 个 `PENDING` | 无可执行账号样本 |

因此本轮没有强行激活账号，也没有把 `PENDING` 账号当作可用测试样本。

## 本机 Camoufox 上游入口可达性（补充证据）

本机 Camoufox 真实导航结果仅作为补充记录；由于本机网络环境可能与 K8S 不同，本轮是否通过以“K8S 集群内真实验收”和服务日志为准：

| 上游入口 | 结果 |
|---|---|
| `chat.deepseek.com` | HTTP 200，页面正常加载 |
| `chat.z.ai` | HTTP 200，页面正常加载 |
| `grok.com` | HTTP 200，页面正常加载 |
| `longcat.chat` | HTTP 200，页面正常加载 |
| `aistudio.xiaomimimo.com` | HTTP 200，页面正常加载 |
| `chat.qwen.ai` | HTTP 200，页面正常加载 |
| `console.x.ai` | HTTP 200，页面正常加载 |
| `agent.minimax.io` | 浏览器导航异常，未得到可验收页面 |
| `cli-chat-proxy.grok.com/v1` | HTTP 404；该地址是 CLI API 根路径，不是网页首页，不能以页面 404 判定 CLI 协议失败 |

## 此前公网/DNS-only 入口基线结果

此前从公网和 DNS-only 入口执行的请求均使用最小文本或 1×1/64×64 PNG fixture，`max_tokens` 不超过 8；只记录协议和事件结果。下面的 K8S 结果才是本轮运行态验收依据。

| Provider/链路 | 请求 | 结果 | 诊断 |
|---|---|---|---|
| GLM Web | 文本、非流式 | HTTP 200；choice/usage 存在，但内容为空 | 协议层成功，业务 completion 不通过 |
| Qwen Web | 文本、非流式 | HTTP 200；choice/usage 存在，内容长度 2 | 真实文本 completion 通过 |
| Qwen Web | 文本、SSE，DNS-only 入口 | HTTP 200；2 个 `data:` 帧，收到 `[DONE]` | 真实流式 Web API 反代通过 |
| Qwen Web | 1×1 PNG 图片 | HTTP 524；STS token 请求 HTTP 200，completion SSE HTTP 200 但 392 字节 | 上游返回不完整事件，服务归类 `empty_model_response`，重试被下游取消 |
| Qwen Web | 64×64 PNG 图片 | HTTP 502 | 浏览器认证面 `Page.wait_for_function` 30 秒超时，归类 `provider_transport_error` |
| DeepSeek Web | 文本、Cloudflare 入口 | HTTP 502 | Cloudflare 判定 origin 响应不完整 |
| DeepSeek Web | 文本、DNS-only 入口 | HTTP 502，`gateway_execution_error` | 已绕过 Cloudflare，仍未形成 completion |
| LongCat Web/CLI | 文本、Cloudflare 入口 | HTTP 502 | Cloudflare 判定 origin 响应不完整 |
| LongCat Web/CLI | 文本、DNS-only 入口 | HTTP 502，`gateway_execution_error` | 已绕过 Cloudflare，仍未形成 completion |

## 未完成项

- Grok Build、Grok Web、Grok Console：当前没有 `ACTIVE + available` 账号，未执行真实 completion。
- MiMo：账号存在，但模型目录当前全部不可用，未执行真实 completion 或图片上传。
- MinMax：没有可用账号，未执行真实 completion 或图片上传。
- 文件、音频、视频：当前线上模型目录没有可执行的对应能力声明，本轮没有把它们伪装成已支持，也没有把厂商产品宣传能力当作 Web/CLI channel 证据。
- 本地未提交迁移尚未部署，因此本报告不能作为本地新 Runtime 的生产验收。

## 结论

本次以 K8S 为准的真实验证证明：Qwen Web API 的文本非流式和 SSE 流式链路可用，且日志确认请求经过真实 Camoufox 页面 Web API；Qwen 图片链路的 STS 授权和建会话可达，但图片 completion 仍失败；GLM 只有协议层成功而无业务内容；DeepSeek、LongCat 在集群内直连服务仍未形成成功 completion。此前 Cloudflare/DNS-only 入口的结果与本次 K8S 结果一致，说明问题不能归因于本机浏览器环境。

当前不能标记“全厂商多模态通过”，也不应部署本地未提交迁移。下一阶段必须在隔离验收 namespace 准备每个 Provider 的 `ACTIVE` 测试账号，按 `provider + model + channel + media kind` 完成文本、图片、文件、音频、视频、流式、取消、账号切换和代理粘性验收后，才能发布新的不可变制品。
