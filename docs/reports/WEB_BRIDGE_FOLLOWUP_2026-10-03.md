# Web 桥接遗留事项处理记录

## 范围与版本

用户要求处理上一轮报告中 Qwen 以外的全部遗留事项。延续 OpenAI Chat/Responses → 厂家 Web 的边界，调用方执行工具。原生产基线为 0.25.2，当前候选为 **0.25.3**；本记录将补齐部署和七家实测证据。

## 0.25.3 变更清单

| 分类 | 模块 | 行为与兼容性 |
|---|---|---|
| 账号可靠性 | ArenaProvider / InferenceCoordinator / CanonicalResponseGuard | `credential_rejected` 最多尝试三个不同账号，排除所有已尝试账号；保留认证隔离、恢复与释放。流式和非流式请求都禁止在有效输出后重试或切换通道。 |
| 流式可靠性 | OpenAiResponseWriter | 快速预检失败仍返回 JSON 和真实 HTTP 状态；上游延迟时，3 秒后发送并刷新 SSE 注释，保留内部换号。延迟失败的 Responses 流补齐 `response.created` → `response.in_progress` → `response.failed`，断连取消上游。 |
| 错误模型 | ProviderFailureSignals / OpenAiResponseWriter | Java/Netty 的超时及厂家 408/504 映射为网关 504；其他厂家 5xx 维持 502；不把厂家凭据失效映射成调用方 Key 401。 |
| Grok 延迟 | grok_web_browser.py | 同域轻量会话页用于 fetch/WebSocket；首帧预算包含浏览器/账号准备；记录 runtime selection、session auth、socket open、conversation attach、first frame、completion 的耗时，阶段事件不泄露到公开流。 |
| 图片预检 | LongcatMediaValidation | PNG/JPEG 的类型、base64、尺寸和 10 MiB 限制在账号租用前校验；无效图片返回 400，原始图片像素和上传流程保留。 |
| Read 查询 | ApiKeyService / ApiKeyGrantStore / AccountRepository | Key 列表与详情使用不含 hash 的扁平 root projection，权限仍批量读取且相互隔离；overview 的两个账户 COUNT 合并为一次查询。 |
| Read 事务 | ProviderRuntimeService / ProviderRuntimeRuleService / RequestLogService / OperationLogService / RegistrationJobService / RegistrationScheduleService | 去除纯 JDBC 读取的多余只读事务，写事务保留；分页、历史修订上界和 JSON 字段保持。 |
| 目录成本 | ModelCatalogCache / ModelCapabilityContract | 当前 L1/L2 目录快照只解码一次并在后台执行，失效仍由原缓存控制；不删减响应字段。仅输出 reasoning 的厂家不再宣告可设置 effort 等级。 |
| 部署 | build-and-deploy.yml / deploy/gitops/server-database-affinity.yaml | 四组件镜像与调度补丁同一 GitOps commit；Server 优先与 PostgreSQL 同节点，保留调度回退；Oracle overlay 明确 namespace，避免手工渲染将 routes 放入 default。 |

## 已完成验证

- Backend `test bootJar`：**420 tests，415 passed，5 skipped，0 failures/errors**；包含真实 PostgreSQL/Liquibase 的 Key projection、权限隔离、批量上界及纯读回归。
- Automation：**496 tests passed**；Grok 的准备超时、预算扣除、阶段隔离与 WebSocket 事件顺序已覆盖；ruff check/format 通过。
- Web：lint、build 通过；六处源码版本与 JAR `Implementation-Version` 为 **0.25.3**，最新迁移 tag 仍为 0.24.0，无新数据库迁移。
- 官方 OpenAI Python SDK **2.54.0** 对本地真实 HTTP 网关与 PostgreSQL fixture：**11 组通过**，包括 namespace/custom、存储/跨 Key 隔离、长历史、并行工具、Chat/Responses 502/504 JSON、延迟 SSE failed。fixture 仅位于测试代码，生产不提供故障注入入口。
- 从实际 workflow 提取 GitOps 更新脚本，在隔离 clone 中连续执行两次：四镜像 tag、调度补丁引用、namespace 保持一致且没有重复引用；Kustomize 输出三个 HTTPRoute 均属于 any2api。

## LongCat 视觉定位

所有诊断只使用本任务生成的图片和现有账号，凭据仅在进程内存使用。

1. 上传后从返回 URL 下载：PNG/JPEG 的 SHA-256、字节数与源文件完全相同；宽高、fileExt、fileKey 与 `multiModal` 路由核验通过。
2. 直接调用 LongCat 原生 Web API：橙色 PNG、JPEG、RGBA、不同尺寸、标准 UUID 文件身份仍会被误判为粉色；蓝色纯色图也误判，绿色正确。该失败可独立于 OpenAI 桥接复现。
3. 两张不同图形/OCR 图片，使用相同且不含答案的 prompt：红三角/蓝方形/6248、绿圆/橙三角/1937 均被正确识别，证明视觉内容实际进入了厂家模型。
4. 独立兼容实验给纯色图增加 4 像素白色边框，原有像素完整保留：橙、蓝、红三组识别正确。此方案改变上游图像尺寸，已向用户请求语义边界选择，尚未加入生产候选。

不能把“上传成功”当成视觉准确率通过，也不能覆盖上述失败记录。

## 部署与生产验收

待补齐 CI、镜像 digest、GitOps revision、Argo/Pod、七家真实 SDK、native 能力、断连/有限并发、公网错误及 Read 前后数据。

## 回滚

0.25.3 无 schema 迁移。可回滚四组件至 0.25.2 不可变镜像，并移除 `server-database-affinity.yaml` 引用恢复原调度；namespace 明确化可以保留。图像兼容方案须作为后续独立候选记录，不覆盖已发布制品。
