# 0.21.0 文档与交付缺漏排查（2026-09-25；09-26 补充核验）

## 核验范围

- 源码：`main@6cbe0251abffc1af665356d30a4b027ae423dd15`，工作树检查前无用户改动；
  Backend、Automation、WEB 源码版本均为 `0.21.0`。
- CI：[GitHub Actions 35939416946](https://github.com/Prodigalgal/any2api/actions/runs/35939416946)
  的 Backend、Automation、WEB 质量检查、三套镜像构建和 GitOps 更新均为 `success`。
- 部署：只读 `kubectl` 检查显示 Argo CD 应用 `any2api` 为 `Synced/Healthy`；
  `any2api-server`、`any2api-automation`、`any2api-web` Deployment 均为 `1/1`，
  镜像 tag 为对应的 `*-sha-6cbe0251...`，业务 Pod 均 Ready、重启 0。
- 数据：2026-09-26 只读 PostgreSQL 查询确认 Liquibase 031 已执行；旧 `grok` / `grok_console`
  的 Provider、账号、API Key 授权行当前均为 0；Arena、Grok Web 的持久化通道模式均为 `AUTO`。
- 边界：未读取生产凭据、账号明细或数据库备份，未发起真实 Provider 推理；
  Pod Ready 与 CI 成功不能证明各 Provider/模型的实际可用性。

## 已确认缺漏（按优先级）

### P1：退役迁移直接删除数据，缺少本仓库要求的版本与回滚边界

[031 迁移](../../backend/src/main/resources/db/changelog/releases/031-retire-legacy-grok-channels.sql)
第 4-13 行直接删除 `grok` 和 `grok_console` 的模型、账号、授权、操作事件及 Provider 行，
末尾仅写 `no rollback required`。生产数据库的 `DATABASECHANGELOG` 已记录该变更集，
旧 Provider、账号和 API Key 授权行当前均为 0。本仓库 `AGENTS.md` 要求破坏性迁移
至少递增 major，并制定迁移、回滚和升级策略；该版本仍为 `0.21.0`。迁移前数据量、
备份及恢复演练未核实，不能从当前 0 行推断迁移前本来为空。

处理建议：先核对迁移执行记录、删除前备份及可恢复性，形成事故或迁移补记；
已执行的 031 不应原地改写。以后退役 Provider 时先做数据导出、引用检查、
灰度禁用与可恢复迁移，并在发布门禁检查版本级别。

### P1：发布流水线缺少版本契约与数据库迁移门禁

[build-and-deploy 工作流](../../.github/workflows/build-and-deploy.yml)
第 28-75 行执行三端测试/构建，但没有版本一致性
校验或空 PostgreSQL 上的 Liquibase apply smoke；仓库测试目录也未发现相关集成测试。
同一工作流第 94、126、132-168 行使用仅含组件与 SHA 的镜像 tag，并在每次 `main` push
后自动更新 GitOps。该 tag 不包含 `AGENTS.md` 要求的日期、业务版本和用途；
对同一 SHA 重新运行时，工作流也没有阻止覆盖已有 tag 的检查。源码版本虽在三个
manifest 中一致，流水线并未验证构建产物、镜像、迁移和发布记录的一致性。

处理建议：在镜像构建和 GitOps 更新前增加版本契约 job；以一次性生成、不可覆盖的
日期/版本/用途 tag 标记候选，并记录 SHA 与 digest；启动临时 PostgreSQL 将完整
Liquibase changelog 应用到空库，检查 schema 与启动。发布策略应明确候选提升及
失败时回退到上一不可变镜像的操作。

### P2：Grok Web 的 Python API binding 无法通过当前 Java 推理入口选择

[`grok_web.py`](../../automation/any2api_automation/providers/grok_web.py)
第 34、41-44 行声明 API/Runtime 并注册 API Action；
[`grok_web_api_actions.py`](../../automation/any2api_automation/providers/grok_web_api_actions.py)
实现直接 WebSocket 推理。但
[`GrokWebProvider.java`](../../backend/src/main/java/com/any2api/provider/grok_web/GrokWebProvider.java)
没有覆盖 `InferenceProvider.supportedTransportModes()`，继承的默认集合仅含 Runtime
（接口第 34-36 行）。
生产持久化模式当前为 `AUTO`，`ProviderTransportModeService.plan()` 因而选择 Runtime，
并拒绝显式 `API`。
现有测试未覆盖 Grok Web 的跨语言通道声明一致性。Arena 在 0.21.0 明确改为 Java
Runtime-only，应与 Grok Web 的未声明原因分开处理。

处理建议：先确定 Grok Web API 是待验收能力还是明确停用能力。若要开放，补 Java
通道声明、模式选择回归测试和真实账号的非流式/SSE/错误分类验收；若继续停用，
保留清晰的能力说明，避免把 Python binding 当作已发布能力。

### P2：全链路规格与 LongCat 模型发现实现不一致

`docs/requirements/FULLCHAIN_PROVIDER_ACCEPTANCE.md` 的验收清单要求每家
`model_discovery` 在 Runtime 与 API 两通道通过；
`automation/any2api_automation/providers/longcat.py:51` 只声明 `chat`，
`LongcatProvider.java` 也未声明 `MODEL_DISCOVERY` 或实现 `discoverModels`。
`docs/architecture/API_CONTRACTS.md` 已将 LongCat API model discovery 标为
`Not declared`。因此旧全链路规格的统一验收条件无法按当前实现满足。

处理建议：确认 LongCat 是否存在可认证的真实目录接口。若有，补两端声明和真实
发现验证；若没有，修订该 Provider 的验收例外与静态目录维护规则，不能把静态
默认模型误记为动态发现通过。

### P2：0.21.0 缺少逐 Provider 的新版本真实验收记录

目前仓库的全链路运行态矩阵最新记到 2026-09-20，`docs/reports/` 中的 API/Runtime
样本更早。0.21.0 于 2026-09-24 提交并已部署，包含 Arena Runtime 选择、Grok Web 注册
改动和生命周期调度修正。本次只确认了 CI、镜像、GitOps 与 Pod 状态，未确认 0.21.0
的模型目录、账号租约、文本非流式/SSE、多模态及生命周期真实调用。

处理建议：按实际 Java 可选通道做脱敏 smoke；至少优先复验本版变更的 Arena、Grok Web
注册与调度时间戳，并记录请求 ID、选中 channel、账号/模型可用状态、错误分类及
对应镜像 SHA。成功样本与健康窗口分别记录，不能用一次 HTTP 200 推断整体 Ready。

### P3：Grok Web 直接依赖 `websockets`，但项目依赖未显式声明

`automation/any2api_automation/providers/grok_web_api_actions.py:11-12` 直接导入
`websockets`，`automation/pyproject.toml` 的 `dependencies` 未声明该包。
当前 `uv.lock` 含 `websockets 16.1.1`，因此现有镜像和 CI 并未因缺包失败；
它依赖其他包的传递依赖继续存在。若以后启用 Grok Web API 或调整上游依赖，
安装结果可能改变。

处理建议：在决定启用该 API binding 时将 `websockets` 作为直接依赖纳入
`pyproject.toml` 与 lockfile，并随新业务版本候选验证，而不是只依赖传递安装。

## 本轮文档处理

- 更新 README、API 契约和当前任务板，并为旧 ADR/任务规格标注适用时点，区分
  0.21.0 源码可选通道、Python binding、历史运行态与本次只读部署证据。
- 将退役的 Grok Build/Console 三通道合同、激活规格和注册流程，以及旧任务板的
  0.18.0 及更早记录移至 `docs/archive/`，保留历史内容；当前任务板只列当前待处理事项。
- 新增 `docs/README.md` 作为当前契约、历史决策、验收快照和本报告的入口。

## 验证

- `git diff --check` 通过；检查 58 个 Markdown 文件，未发现缺失的相对链接。
- 本轮只修改文档，没有运行本地 Java/Python/WEB 构建；上述 CI 成功针对的是
  `6cbe025` 源码基线，尚未覆盖本次文档差异。
