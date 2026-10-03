# OpenAI API 到厂商 WEB 的桥接（0.26.1）

## 范围

调用方使用 OpenAI API，后端负责转译到各厂商现有 WEB 请求 / Browser Runtime。重点是模型发现、Chat Completions / Responses 的文本与多轮、JSON/SSE、function 工具调用及结果回传，图片按厂商 WEB/模型能力提供。后端不执行调用方的工具。已有 API / RUNTIME / AUTO 内部通道选择保持兼容，不意味着对齐厂商官方 API。

不以完整 OpenAI 协议兼容作为目标；namespace/custom 和状态资源的现有实现保留，扩展按需使用。Codex 配置、模型 metadata 和本机工具策略属于可选客户端事项，不纳入后端交付。

## 当前能力

使用 `/v1/chat/completions` 和 `/v1/responses`，或 `/{provider}/v1` 前缀。统一入口的 `model` 使用 `provider/upstream-model`，实际可用模型和账号以运行态 `/v1/models` 为准。

| 能力 | 支持范围 |
|---|---|
| JSON / SSE | Chat 与 Responses；SDK 可重建最终输出和工具参数 |
| 模型详情 | `models.retrieve()` 使用目录返回的 model ID；缺失或无权限返回 OpenAI JSON 404 |
| 编码模型 ID | 应用安全链仅对 GET 模型详情 ID 放行 `%2F`；当前共享 Envoy 入口先同源 307 归一化，普通 SDK 自动跟随 |
| 严格函数参数 | 显式 `strict:true`，由公共网关检查完整参数，再输出工具事件；上游生成不合格会失败 |
| function tools | 八家现有 WEB Provider 均有模拟工具桥接；auto/none/required/指定 function 按模型校验。Qwen 缺账号，真实验收与其他模型限制见发布报告 |
| namespace / custom（可选扩展） | namespace 函数与 text custom 转 function，再还原输出身份；并行调用与 allowed_tools；不保证每个 WEB 上游稳定支持 |
| 历史回放 | 多轮文本、function call/output；保留已有 reasoning、phase、custom、refusal；图片结果需模型和 Key 的媒体权限，适配情况见真实验收 |
| 状态 | `store:true`、`previous_response_id`、retrieve/delete/input_items，按 Key 隔离 |
| 上下文 | 默认和 `truncation:disabled` 保留完整历史；仅显式 `truncation:auto` 使用 32 条裁剪目标，保留首部规则和完整工具组。厂家明确的消息上限、既有 token/request-size 限制仍校验 |

custom grammar、defer_loading/tool search、原生 hosted tools、opaque encrypted-only reasoning、item_reference、WebSocket、background、Conversations、`/responses/compact`、流式续传均未形成通用实现保证。请求会按具体 Provider 契约明确拒绝。请求 `include:["reasoning.encrypted_content"]` 不会获得伪造的加密内容。

严格 function 参数支持基础类型、nullable、嵌套 object/array、enum、anyOf、数值/长度约束和局部非循环引用。object 必须设置 `additionalProperties:false` 并声明全部必填字段；pattern/format、循环或外部引用及其他未支持关键词明确返回 400。严格模式缓冲工具事件到参数完成并通过校验，失败返回 `tool_call_generation_failed`；它不承诺厂家原生受限解码或一定生成成功。Responses 未声明 strict 时仍保持既有非严格行为。完整边界见 [API 契约](../architecture/API_CONTRACTS.md)。

工具上限、schema、媒体、generation 参数和 token 预算仍由当前 Provider 校验。MiMo/LongCat 现有工具上限是 128。能力声明与真实厂商验收、模型可调用状态是不同证据。

0.25.0–0.25.2 为现有厂家补齐 emulated function 桥接，0.25.3–0.25.7 的真实验收见 [后续修复与验证](../reports/WEB_BRIDGE_FOLLOWUP_2026-10-03.md)：七家所选模型完成 SDK、完整历史和有限并发检查，LongCat 用户/工具 OCR、TXT/PDF 已通过，厂家原生纯色误判仍保留。Qwen 仅有代码与离线契约证据。本轮模型详情和 strict 的逐厂商结果见 [0.26.0–0.26.1 客户端契约验收](../reports/OPENAI_CLIENT_CONTRACT_2026-10-03.md)。

### 历史验收（0.24.4，后续修复见当前发布报告）

最终 0.24.4 的 MiMo `mimo-v2.6-flash`、LongCat `longcat-flash` 官方 SDK 7 组均通过，包含 Chat/Responses 文本工具闭环与可选扩展。MiMo 的标准 Responses 工具图片结果回放也通过。LongCat 工具图片回传尚有 502/熔断缺陷；Grok 的普通 Responses/SSE/function 续接通过前三组，namespace 扩展返回空输出；独立的常用 Chat required function 在 120s 客户端超时，Chat 回传/SSE 未执行。不能将任何一家这些结果扩展到其他模型或全部厂商。证据见 [发布验收与性能](../reports/RELEASE_AND_READ_PERFORMANCE_2026-10-02.md)。

## 常用 API

主要入口为 `GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/responses`。`messages` / `input` 和多轮历史由调用方提供；工具输出按 call_id 回传。Responses 也可使用已实现的 `store:true` / `previous_response_id` 续接，无需厂商 WEB 提供同名资源接口。

```python
import os
from openai import OpenAI

with OpenAI(
    base_url="https://any2api-direct.mnnu.eu.org/v1",
    api_key=os.environ["ANY2API_MIMO_API_KEY"],
    timeout=120,
) as client:
    chat = client.chat.completions.create(
        model="mimo/mimo-v2.6-flash",
        messages=[{"role": "user", "content": "你好"}],
    )
    print(chat.choices[0].message.content)
    response = client.responses.create(
        model="mimo/mimo-v2.6-flash", input="你好", store=False,
    )
    print(response.output_text)
```

示例模型需按当前 `/v1/models` 和 Key scope 选择；120s 是真实 WEB 验收的显式客户端超时，不是延迟目标。流式设置 `stream=True`，工具/媒体按下述权限及模型能力使用。

## 可选：Codex 配置与历史实验

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

历史验收使用 Codex CLI 0.159.2；0.25.5 的只读图片工具闭环使用 0.160.0。实验关闭 apps/plugins/multi_agent，使用 HTTP/SSE、函数工具和 `read-only` sandbox。自定义路由名可能触发 CLI model metadata fallback 提示；已有 OpenAI 原生模型的 metadata 不应被用来承诺本桥接不支持的 grammar/encryption 或完整原生 strict 解码能力。

0.24.0 的受控上游实验完成 `view_image` 工具闭环；本机 PowerShell 命令工具被 CLI policy 拒绝，未绕过。0.24.4 真实 MiMo 图片实验返回正确颜色，但 CLI JSON 没有显式 image_view completion item，不能仅凭最终标记认定该工具执行轨迹。标准 API 的工具图片回放已单独验证。Shell/patch 权限和 metadata fallback 不属于本项目后端缺口。

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
python tools/compatibility/openai_strict_smoke.py --base-url http://127.0.0.1:18089/v1 --model mimo/fixture --provider mimo --fixture --sdk-path backend/build/agent-interop-python --report backend/build/strict-sdk-report.json
python tools/compatibility/check_versions.py --jar backend/build/libs/any2api-backend-0.26.1.jar
```

关闭本地服务：

```powershell
python -c "import urllib.request; urllib.request.urlopen(urllib.request.Request('http://127.0.0.1:18089/__fixture/shutdown', data=b'', method='POST')).read()"
```

测试报告不记录 Key、完整 prompts 或真实客户端元数据。测试服务和 PostgreSQL 在关闭后退出；生成物位于 Git 忽略的 `backend/build`。

## 真实环境验收

用户已授权本轮生产部署与现有凭据测试。标准 API/官方 SDK 验收时，将脚本的 base_url/model 改为已部署候选，使用限模型/协议/feature 的临时 Key，去掉 `--fixture`。实际需要 function 的模型必须按 tool_choice 生成结构化调用、保留 call_id 并消费回传结果。脚本包含 namespace/custom 扩展，完整脚本失败须按具体检查区分，不能把扩展失败写成常用协议整体失败。可选 Codex 命令脚本对本地工具权限的要求单独判断。

模型能力不支持 reasoning 时（例如当前 Grok Web），SDK smoke 加 `--no-reasoning`，报告记录该参数被省略；直接请求 unsupported reasoning 会返回 400。MiMo 验收优先使用当前仍有成功探针的模型，历史模型名称存在于目录不代表上游仍支持工具调用。

真实上游首帧可能超过 smoke 默认 30s，本轮用 `--core --no-reasoning --timeout 180` 单独核验常用协议闭环。报告记录客户端超时；增大测试超时不代表厂商延迟达到生产目标。Chat assistant 工具历史的缺省/null content 会在 canonical messages 规范为无文本，原始请求和工具身份保留。

先验证实际使用厂商的普通对话/SSE/function 循环，再验证需要的图片、长对话、失败、续接和并发；不要求所有 WEB 上游实现相同扩展。记录源码版本、不可变镜像、GitOps/Pod、模型、Key scope、SDK 版本和请求结果。本轮真实 SDK 使用 direct 入口；public 的 LongCat 参数错误已验证为 OpenAI JSON 400。Cloudflare 曾将 502 body 包装为通用错误，真实上游 502/504 的完整透传仍需专门核验。新增外部账号/凭据或破坏性操作遵守 AGENTS.md Stop Conditions。

## 状态与兼容边界

`store` 缺省 false；只有 `store:true` 创建公共网关资源。既有原生 Provider state 和上游保存的历史保持自己的生命周期。默认网关保留 24 小时、单条 input+response 2 MiB、每个归属最多 1000 条活跃记录；配置项见 [API 契约](../architecture/API_CONTRACTS.md)。

续接会合并旧 input/output，但不继承旧 instructions；必须使用原厂商/模型，随机续接自动固定原路由。每次读取、删除或续接都重新检查当前权限。跨 Key、跨 provider hint、缺失和过期资源返回 404；权限收回返回授权错误。

`input_items` 支持 asc/desc 和稳定游标，分页默认 20、上限 100。运行中删除不会被后续完成或取消重新插入。取消状态写入为异步收尾，数据库不可用时仅能记录失败日志，历史由 TTL 清理。

API 增量兼容，普通文本与厂商通道选择保持既有契约。缓存 key 升级失效旧的不完整缓存；工具/状态/推理/结构化请求不使用文本缓存。迁移 032 增加网关状态表和原生状态的 Key 归属，旧的无归属 Grok state 对分发 Key 采取拒绝访问。

## 参考

- [OpenAI gateway compatibility](https://learn.chatgpt.com/docs/enterprise/gateway-compatibility)
- [Function calling](https://developers.openai.com/api/docs/guides/function-calling)
- [当前部署与真实厂商验收](../reports/WEB_OPENAI_BRIDGE_2026-10-03.md)
- [历史 0.24.4 发布与性能](../reports/RELEASE_AND_READ_PERFORMANCE_2026-10-02.md)
- [历史本地候选验收](../reports/OPENAI_AGENT_ACCEPTANCE_2026-10-02.md)
