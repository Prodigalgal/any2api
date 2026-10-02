# OpenAI SDK 与 Codex 接入（0.24.0）

## 当前能力

使用 `/v1/chat/completions` 和 `/v1/responses`，或 `/{provider}/v1` 前缀。统一入口的 `model` 使用 `provider/upstream-model`，实际可用模型和账号以运行态 `/v1/models` 为准。

| 能力 | 支持范围 |
|---|---|
| JSON / SSE | Chat 与 Responses；SDK 可重建最终输出和工具参数 |
| function tools | 当前 MiMo、LongCat、Grok Web 的模拟工具实现；其他厂商沿用自己的工具能力 |
| namespace / custom | namespace 函数与 text custom 转 function，再还原输出身份；并行调用与 allowed_tools |
| 历史回放 | reasoning、phase、function/custom call 与 output、refusal；图片结果需模型和 Key 的媒体权限 |
| 状态 | `store:true`、`previous_response_id`、retrieve/delete/input_items，按 Key 隔离 |
| 上下文 | 默认 32 条消息裁剪目标，保留首部规则和完整工具组；`truncation:disabled` 超限报错 |

`strict:true`、custom grammar、defer_loading/tool search、原生 hosted tools、opaque encrypted-only reasoning、item_reference、WebSocket、background、Conversations、`/responses/compact`、流式续传均未形成通用实现保证。请求会按具体 Provider 契约明确拒绝。请求 `include:["reasoning.encrypted_content"]` 不会获得伪造的加密内容。

工具上限、schema、媒体、generation 参数和 token 预算仍由当前 Provider 校验。MiMo/LongCat 现有工具上限是 128；开启全部插件可能超过此上限。能力声明与真实厂商验收、模型可调用状态是不同证据。

## Codex 配置

在独立测试配置中选择真实存在、声明 function tools 的模型。示例中的模型 ID 必须替换为当前目录里的有效 ID：

```toml
model_provider = "any2api"
model = "mimo/REPLACE_WITH_ENABLED_MODEL_ID"
web_search = "disabled"

[model_providers.any2api]
name = "Any2API"
base_url = "http://127.0.0.1:8080/v1"
env_key = "ANY2API_E2E_API_KEY"
wire_api = "responses"
supports_websockets = false
```

通过环境变量提供已授权的测试 Key。分发 Key 需允许 Responses、对应 provider/model，以及 `TOOL_CALLING`；读取并回传本地图片还需 `MULTIMODAL_INPUT` 和 `FILE_UPLOADS`。全权限静态入口使用共享 `system` 状态归属，需独立归属时使用分发 Key。

本次验收使用 Codex CLI 0.159.2，关闭 apps/plugins/multi_agent，使用 HTTP/SSE、函数工具和 `read-only` sandbox。自定义路由名可能触发 CLI model metadata fallback 提示；已有 OpenAI 原生模型的 metadata 不应被用来承诺本桥接不支持的 grammar/strict/encryption 能力。

本机 PowerShell 命令工具被 CLI policy 拒绝，未绕过该策略。通过 `view_image` 成功执行本地图片读取、回传工具结果和图片输入、取得最终回答。Shell/patch 工具的执行权限需要在实际客户端环境单独验证。

## 可复现的本地验收

测试服务只监听 `127.0.0.1`，使用真实 PostgreSQL 与完整 Liquibase 链，以及生产 parser/controller/writer/state/auth filter。账号认证、InferenceCoordinator 和上游生成使用 fixture；MiMo request mapper/Provider 校验仍执行。它证明客户端和协议互操作，不能替代真实厂商或部署验收。

从仓库根目录安装隔离的测试 SDK：

```powershell
python -m pip install --target backend/build/agent-interop-python openai==2.54.0
```

另一个终端启动服务：

```powershell
Set-Location backend
.\gradlew.bat --no-daemon agentInteropServer
```

从仓库根目录运行：

```powershell
$env:ANY2API_E2E_API_KEY = 'fixture-primary'
python tools/compatibility/openai_agent_smoke.py --base-url http://127.0.0.1:18089/v1 --model mimo/fixture --fixture --sdk-path backend/build/agent-interop-python --report backend/build/agent-sdk-report.json
python tools/compatibility/codex_agent_smoke.py --base-url http://127.0.0.1:18089/v1 --model mimo/fixture-codex-image --fixture --tool image --report backend/build/agent-codex-report.json
python tools/compatibility/check_versions.py --jar backend/build/libs/any2api-backend-0.24.0.jar
```

关闭本地服务：

```powershell
python -c "import urllib.request; urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18089/__fixture/shutdown', data=b'', method='POST')).read()"
```

测试报告不记录 Key、完整 prompts 或真实客户端元数据。测试服务和 PostgreSQL 在关闭后退出；生成物位于 Git 忽略的 `backend/build`。

## 真实环境验收

在授权的测试环境中，将脚本的 base_url/model 改为已部署候选，使用授权 Key，去掉 `--fixture`。SDK 验证真实模型必须能够按指定 tool_choice 生成调用；Codex 脚本默认要求本地目录只读命令成功，并验证结果进入最终回答。

模型能力不支持 reasoning 时（例如当前 Grok Web），SDK smoke 加 `--no-reasoning`，报告记录该参数被省略；直接请求 unsupported reasoning 会返回 400。MiMo 验收优先使用当前仍有成功探针的模型，历史模型名称存在于目录不代表上游仍支持工具调用。

真实上游首帧可能超过 smoke 默认 30s，可用 `--timeout 120` 单独核验协议闭环。报告记录客户端超时；增大测试超时不代表厂商延迟达到生产目标。Chat assistant 工具历史的缺省/null content 会在 canonical messages 规范为无文本，原始请求和工具身份保留。

先分别验证 MiMo、LongCat、Grok Web 的工具循环，再验证实际需要的媒体、长对话、失败、续接和并发。记录源码版本、不可变镜像、GitOps/Pod、模型、Key scope、客户端版本和请求结果。真实厂商凭据与外部环境写入遵守 AGENTS.md Stop Conditions。

## 状态与兼容边界

`store` 缺省 false；只有 `store:true` 创建公共网关资源。既有原生 Provider state 和上游保存的历史保持自己的生命周期。默认网关保留 24 小时、单条 input+response 2 MiB、每个归属最多 1000 条活跃记录；配置项见 [API 契约](../architecture/API_CONTRACTS.md)。

续接会合并旧 input/output，但不继承旧 instructions；必须使用原厂商/模型，随机续接自动固定原路由。每次读取、删除或续接都重新检查当前权限。跨 Key、跨 provider hint、缺失和过期资源返回 404；权限收回返回授权错误。

`input_items` 支持 asc/desc 和稳定游标，分页默认 20、上限 100。运行中删除不会被后续完成或取消重新插入。取消状态写入为异步收尾，数据库不可用时仅能记录失败日志，历史由 TTL 清理。

API 增量兼容，普通文本与厂商通道选择保持既有契约。缓存 key 升级失效旧的不完整缓存；工具/状态/推理/结构化请求不使用文本缓存。迁移 032 增加网关状态表和原生状态的 Key 归属，旧的无归属 Grok state 对分发 Key 采取拒绝访问。

## 参考

- [OpenAI gateway compatibility](https://learn.chatgpt.com/docs/enterprise/gateway-compatibility)
- [Function calling](https://developers.openai.com/api/docs/guides/function-calling)
- [本次验收记录](../reports/OPENAI_AGENT_ACCEPTANCE_2026-10-02.md)
