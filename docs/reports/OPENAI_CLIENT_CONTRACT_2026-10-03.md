# 统一 OpenAI 客户端契约验收（0.26.0）

## 范围与基线

用户要求沿统一 OpenAI 客户端契约与各厂商 WEB 适配继续推进。本轮补齐模型详情和显式严格函数参数；规格见 [执行范围](../requirements/OPENAI_CLIENT_CONTRACT_FOLLOWUP.md)。原生产为 0.25.7，源码基线 41054fd，GitOps 0f7ce4d，执行前 Argo Synced/Healthy、四组件 Ready。

## 已实现

- 统一/厂家 `models.retrieve()` 与模型目录共享字段和运行态；支持带斜线 ID，缺失、禁用厂家、越权返回 `404 model_not_found`，不泄露模型是否存在。
- strict function schema 使用 Jackson 3 的 networknt 3.0.8 校验器，编译缓存上限 128、空闲十分钟失效；输入关键词、引用、大小、深度和展开成本有界。仅局部非循环引用，schema 校验不读取外部资源。
- MiMo、LongCat 及共享工具桥接的明确 strict 拒绝改为统一前置检查；canonical 流在严格工具参数完整、匹配 delta 且符合 schema 后释放工具事件。不合格参数不会发送给客户端，不修补字段或伪造成功。
- 网关 strict 能力独立于旧发现记录，模型缓存命名空间升为 v5，避免滚动时读取旧进程的能力快照。
- 默认 store/strict 行为、Key、权限、原始媒体、搜索/推理和已有 WEB 通道保留。新增向后兼容接口与能力使用 0.26.0，无数据库新迁移。

## 验证状态

- Backend：461 项，456 passed / 5 条件 skipped / 0 failure/error，bootJar 构建通过；源码/JAR/Web/Automation 七处版本均为 0.26.0，migration tag 仍为 0.24.0。
- Automation 回归、ruff、Web lint/build 通过。Python SDK 2.54.0 的真实 HTTP + PostgreSQL fixture：既有常用/扩展 12 组通过，新增模型详情、strict/Pydantic Chat、Responses SSE/结果回放和无效严格参数失败 6 组通过。
- 无效严格参数的 fixture 确认收到 `response.failed`，未收到任何 function 参数事件或 function output item。官方 SDK 的 `get_final_response()` 仅接受成功终态，失败测试读取标准 failed 事件的 response，不将 SDK 成功 helper 用于失败终态。
- 当前尚未部署或完成真实厂商 strict 验收；候选与原生产状态分别记录。

本地证据位于 Git 忽略的 `backend/build/contract-v0260-backend-full.log`、`contract-v0260-local-summary.json`、`strict-v0260-fixture.json`、`common-v0260-fixture.json`。报告不包含 Key、真实用户内容或完整工具参数。

## 保留边界与回滚

严格函数参数检查不等于原生受限解码，也不能保证上游生成成功；pattern/format、循环/外部引用和其他未支持关键词明确拒绝。Responses 默认 `store:false`、strict 缺省非严格行为与 OpenAI 默认值仍有差异。WebSocket、background、Conversations、compact、hosted tools 不在本轮范围。Qwen 账号、公网 Cloudflare 502/524 和 LongCat 原生纯色识别问题仍按既有记录保留。

回滚为 0.25.7 四组件不可变镜像，后缀 `20261003-v0.25.7-release-378475fe2fe201d53e4f8da6408cecf1909df346`。无需恢复数据库或 Key；回滚后模型详情/严格函数能力撤回，原有普通调用继续按旧契约运行。
