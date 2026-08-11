# Any2API 管理控制面现代化

## 目标

- 将现有管理站升级为可响应式使用的 MUI 运维控制台，提升高频扫描、筛选和操作效率。
- 账号列表内直接打开大尺寸详情弹窗，保留筛选、分页和滚动上下文。
- 账号推理探测必须先选择该厂商已编目的模型，并返回真实上游文本与明确失败分类。
- 支持按“厂商 + 模型”设置 `max_context_tokens`、`max_input_tokens`、`max_output_tokens` 管理员上限。
- 支持按厂商设置账号保活间隔、确定性抖动窗口和远端自动化参数。

## 范围

- `models` 持久化管理员 token 上限，模型目录输出发现值、覆盖值和有效值。
- 网关在选择具体厂商和模型后、占用账号前执行统一 token 预算校验。
- 新增独立账号探测端点，保留原端点兼容；管理站改用新端点。
- 新增模型策略页，集中管理模型 token 上限和厂商保活策略。
- 重构 MUI theme、App Shell、页面布局与账号池关键交互，支持窄屏导航和表格横向滚动。

## 非目标

- 不实现厂商专用 tokenizer；输入 token 使用统一、偏保守的 UTF-8 估算，并在错误中明确标注估算值。
- 不改变公开 OpenAI-compatible 请求参数名称或厂商 adapter 的参数映射。
- 不删除现有账号详情路由；其作为可分享、可刷新和兼容入口继续保留。
- 不在本轮重写 Python 注册自动化或现有 provider 协议实现。

## 关键影响文件

- `backend/src/main/resources/db/changelog/releases/018-governance-policies.sql`
- `backend/src/main/java/com/any2api/provider/ModelCatalogCache.java`
- `backend/src/main/java/com/any2api/provider/ModelTokenPolicyService.java`
- `backend/src/main/java/com/any2api/provider/ModelRequestLimitGuard.java`
- `backend/src/main/java/com/any2api/lifecycle/AccountProbeService.java`
- `backend/src/main/java/com/any2api/lifecycle/InferenceReadinessProbe.java`
- `backend/src/main/java/com/any2api/settings/RuntimeSettingsService.java`
- `web/src/theme/theme.ts`
- `web/src/components/app-shell.tsx`
- `web/src/components/accounts.tsx`
- `web/src/components/model-policies.tsx`

## 验收标准

- 管理员可按厂商和模型设置、修改、清空三类 token 上限；覆盖值不得超过已知厂商发现值。
- `/v1/models` 与 `/api/catalog/v1/models` 返回相同有效限制，并同时说明发现值与管理员覆盖值。
- 超过输出、输入或总上下文限制的请求在调用厂商和占用账号前返回 `invalid_request_error`。
- 账号探测可选择模型，禁止跨厂商或未编目模型，界面能显示真实输出、耗时和错误类型。
- 重复点击探测不会产生并行前端请求；长探测使用独立路径，便于网关设置更长超时。
- 厂商保活间隔与 jitter 对后续调度生效；保留字段不能被自定义参数覆盖。
- 1440px 桌面和 390px 移动视口不存在强制最小宽度、文字重叠或不可达导航。

## 测试方式

- `backend/gradlew.bat test`
- `npm run lint`、`npm run build`（`web/`）
- 浏览器完成账号筛选保留、详情弹窗、模型探测、模型限制保存/清空、保活策略保存的 smoke。
- 对公开模型目录和超限请求执行 HTTP smoke，确认返回契约和错误类型。

