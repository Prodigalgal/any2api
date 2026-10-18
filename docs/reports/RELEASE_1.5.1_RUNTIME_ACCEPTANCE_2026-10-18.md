# 1.5.1 MiMo 流桥接修复发布与运行验收

## 发布事实

2026-10-18，时间统一 UTC+8。用户授权“推送部署更新”后，已将完成本地验收的 MiMo SSE 修复提交并推送 main，沿现有 CI/GitOps 发布到生产。

- 发布源码：`77b5dbc95ea98b2a1ed14b020fcbd4233f06ca9c`。
- [CI 39112203021](https://github.com/Prodigalgal/any2api/actions/runs/39112203021)：成功；三项质量门禁、六个镜像构建与 GitOps 更新完成。原浏览器基础镜像未重新构建；应用镜像继续生成 SBOM/provenance。
- GitOps：`3e09566807f989f2bb42f0ea7894762e979d8488`，提交时间 16:43:16。
- 生产：1.5.1，Argo `Synced/Healthy`，同步操作 `Succeeded`。Server 于 16:45:48 Ready；验收时四个运行中的应用 Pod 均 Ready、零重启，旧 Pod 已退出。
- 规格与回滚：[发布执行计划](../requirements/RELEASE_1.5.1_MIMO_STREAM_REPAIR.md)。

## Changelog

### MiMo 桥接修复

- `api_transport.py`：增加仅由 MiMo 启用的 `preserve_sse_events`；按帧保留 `event`、多行 data 和默认 message 类型，对累计帧大小进行限制。其他厂家继续原解析方式。
- `mimo_api_actions.py` / `mimo_browser.py`：API 与 Runtime 均保留事件名称；浏览器在 finish 时取消 reader，不等待连接自行关闭；终止帧前发送凭据更新，保留 cookie 续期链路。
- `MimoProvider` / `MimoEventDecoder`：过滤 dialogId、提示、搜索等控制信息，finish 不再作为正文 `[DONE]`；映射 SSE error、messageType error、敏感拦截及中英文维护提示；仅控制/推理而无正文的输出不能成功；收到终止事件及时释放上游。
- `InferenceTelemetryService`：新快照增加 `eventType`，可以区分 `ReasoningDelta` 与 `OutputTextDelta`；旧快照保持原样。
- 新增 Java 12 项、Python 4 项回归；全端及锁文件统一版本 1.5.1。

### 根因与兼容边界

- 生产 1.5.0 在 15:45–16:15 发起的 28 条 MiMo 请求首段均为 8 位数字；18 条后台成功记录末段为字面量 `[DONE]`，另有 10 条下游取消。
- MiMo v3 依赖 SSE `event` 区分 dialogId/message/usage/finish/error；两条旧传输路径丢弃该字段，后端再将任意 content 当作正文，造成控制帧污染与错误的成功依据。
- 保留旧文本格式、正常数字回答、推理、工具桥接及 Chat/Responses 渲染；公开接口不变，无数据库迁移。Backend/Automation 成套更新。
- 保持 DeepSeek、Qwen 停用和零副本，不改变账号、分发密钥、代理池或生成探活策略。

## 验证结果

### 代码、制品与 CI

- Backend `test bootJar` 成功：601 项中 596 passed、5 项需显式开启的 Grok 在线互操作用例跳过，失败/错误 0。
- Automation：577 passed；128 个 Python 文件 Ruff format check 与 Ruff check 通过。
- Web：ESLint 与 Next.js 生产构建通过。
- 统一版本校验通过，源码、锁文件、Automation API 与 `any2api-backend-1.5.1.jar` 均为 1.5.1。
- 合成 SSE 验证覆盖 CRLF、多行 JSON、UTF-8 跨块、finish 后连接不关闭、reader 取消、迟到数据过滤；不消耗厂家生成额度。
- CI 的 Backend、Automation、Web 质量门禁及六个不可变应用镜像构建均成功。

### 生产版本与镜像

统一 tag 后缀为 `20261018-v1.5.1-release-77b5dbc95ea98b2a1ed14b020fcbd4233f06ca9c`，镜像仓库为 `docker.io/speedproxy/any2api`。下列 digest 为运行时报告的 OCI index digest，均与 registry 的该不可变 tag 一致；同时读取了对应 linux/arm64 manifest。

| 服务 | Pod | 运行 digest |
| --- | --- | --- |
| server | any2api-server-6c68fdb7df-qljjh | `sha256:d2a137226d24136b038a5e193c01d133342403b9c19aee44440cb8c46717c723` |
| automation | any2api-automation-657d885798-z7x22 | `sha256:3461b007d0d8ba3626c35a185debdec9e4178589c1ad0b89e865582ba8be98de` |
| automation-arena | any2api-automation-arena-68d6d569f9-52s57 | `sha256:90f74eeafd25500ad5ffcbd96733eb6877e62eece2243d3eecbb69d648c35a01` |
| web | any2api-web-5cfc6b5fbb-m25wg | `sha256:9bf12e927b95a4adc8e738e16c1613ada2c03dd73bcebf3b2e43bdae44d9bf6a` |

- Server 实际启动版本、两套 Automation 的安装包/API/pyproject 版本检查器、Web 实际 package.json 均为 1.5.1；不是仅核对镜像 tag。
- PostgreSQL、Redis Ready、零重启；DeepSeek/Qwen 数据库 enabled=false，Deployment replicas=0，未恢复运行。
- 两个公网入口 health/ready 均 200；匿名 Chat/Responses 均 401、OpenAI JSON 含 request_id。
- 使用已有厂家独立 Key 读取两入口的 MiMo、MiniMax、Grok、Arena、GLM、LongCat 目录，均 200 且模型归属正确。目录检查不证明厂家生成恢复。
- 滚动期间 Arena 曾被 namespace CPU quota 暂时拒绝创建，新 Pod 创建后自动恢复；另有启动期端口尚未监听的 readiness 告警。保留相关事件，未改配额或强制删除 Pod。

### MiMo 真实流量与遗留边界

- 稳态验收窗口从数据库时间 **16:46:14.052198** 开始，避免将旧 Server 的在途请求当作新版本证据。
- 截至数据库时间 **16:49:34**，该窗口已有 1 条 MiMo 自然请求结束，服务端记录了独立正文与正常终止。请求 `61f7b5bd-a22c-442b-ac9f-0571169a3716`，模型 `mimo-v2.6-pro`，Chat Completions，耗时 48,233ms、TTFB 8,085ms。
- 该样本有 716 个 ReasoningDelta（6,996 字符）、10 个 OutputTextDelta（165 字符）、1 个 Usage、1 个 Completed(stop)；正文首段不是 8 位数字，也没有独立的 `[DONE]` 正文片段。没有输出或保存其私密文本。
- 该证据证明新版本能在真实请求中分离推理/正文并过滤已知控制污染；48.2 秒的样本仍说明长推理延迟存在，另有请求在生成中，不能据此宣告全部请求或客户端恢复。
- 本次未发送厂家生成探活；Responses、工具调用与流式正文契约有本地回归，线上生成结论仅根据后续真实业务记录形成。
- 原快照没有输出事件类型，无法可靠分离历史推理/正文或直接按客户端的“有效输出”标准重算。
- 长耗时、downstream_cancelled、Arena 原有风控故障仍需独立处理；本次部署健康不代表这些问题消失。

## 运维证据与回滚

- 脱敏证据位于本地忽略目录 `backend/build/`：`production-v151-baseline-20261018.json`、`production-v151-ci-20261018.json`、`production-v151-verified-20261018.json`、`production-v151-registry-digests-20261018.json`、`production-v151-public-20261018.json`、`production-v151-rollout-events-20261018.json`、`production-v151-mimo-traffic-20261018.json`。
- 证据只存状态、版本、digest、计数、耗时和请求 ID，不保存 API Key、凭据、用户提示或回答正文。
- 回滚恢复 1.5.0 的原不可变镜像组合：后缀 `20261018-v1.5.0-release-db4fdf5b0fc023987ebf21394cd88d2acba15b03`；保留 DeepSeek/Qwen 零副本覆盖，无数据库回滚。该版本包含已知 MiMo 控制帧污染，回滚后必须明确记录。
