# 1.5.5 浏览器准备等待与异步清理修复

本文时间为 UTC+8。范围与事实见 [任务规格](../requirements/ARENA_SESSION_RECOVERY_1.5.5.md)。

## Changelog

### 浏览器稳定性：`official_browser.py`

- 合并正常关闭和启动失败的重复清理路径。context、browser/manager、driver 各阶段使用已有 `browser_cleanup_timeout_seconds`，异常记录阶段和错误类型后继续回收。
- 沿用进程 deadline watchdog，避免驱动在接收协程取消后又挂在 `finally` 中；残留进程终止移至线程，避免同步等待阻塞事件循环与健康请求。
- 清理任务受 shield 保护，调用方取消后仍等清理完成，再释放浏览器预算和隧道引用。正常缓存命中继续复用会话与代理，不改为空闲即销毁。
- 增加等待账号会话、取得槽位、启动与清理耗时日志；不打印账号、Cookie、代理认证、验证码 Token 或私密内容。

### 请求边界：`provider_api.py` / `page_fetch_browser.py`

- Action 流使用已有 `inference_first_frame_timeout_seconds`（默认 90 秒）限制第一内部帧前的准备等待；到期取消准备并返回内部 504/status+error，由后端沿既有错误模型转换为 OpenAI 响应。
- 该预算包含取消/资源回收所需时间，不能承诺所有故障精确在第 90 秒结束；既有同步代理获取需等有界的线程操作退场，以免泄漏租约。
- 第一帧后恢复原有流处理，不为正常长流增加此 90 秒总时长上限。流结束、消费者关闭或取消时显式关闭迭代器，保留上游已有状态与错误分类。
- 非流式页面请求的等待槽位、浏览器恢复、页面请求与凭据状态读取使用相同预算，防止目录和后台保活永久占住槽位。

### 兼容性与版本

- Backend、Web、Automation 和锁文件统一为 1.5.5。无数据库迁移、下游 OpenAI 请求字段变化、管理 API 或权限变化。
- DeepSeek/Qwen 继续停用；各厂家代理绑定与 Oracle 固定 IPv6 服务保持。未恢复模型可用性判定、定时生成探活或批量账号试发。

## 本地验证

| 验证 | 结果 |
| --- | --- |
| Automation | 636 passed，包含真实 Redis 集成；新增关闭卡住/失败、启动中取消、watchdog 处理驱动 finally、事件循环可调度、槽位与引用释放、首帧超时、取消、消费者关闭、正常长流等回归 |
| Ruff | check 与 136 文件 format check 通过 |
| Backend | test 缓存有效（原 596 通过、5 条 opt-in 跳过）；1.5.5 bootJar 成功，CI 重新运行完整门禁 |
| Web | ESLint、Next.js 生产构建、TypeScript 通过 |
| 版本 | 源码与 `any2api-backend-1.5.5.jar` 契约通过，最近迁移 tag 1.5.0 未变 |

保留 1 条既有 Starlette/httpx 弃用警告。测试中的故障注入、合成帧与空槽回收不能代替 Arena 有效回答验收。

## 发布与运行态

- 源码 `0a2a0a97360597a8a98c411b36fd7559c06147f9`，CI [39134655792](https://github.com/Prodigalgal/any2api/actions/runs/39134655792) 成功；GitOps `0de384516e64a37f732517f39160462e0aae1111`，Argo Synced/Healthy。
- 四应用实际版本均为 1.5.5，验收快照均 Ready、零重启；运行 digest 与 registry arm64 制品一致。镜像后缀 `20261018-v1.5.5-release-0a2a0a97360597a8a98c411b36fd7559c06147f9`。
- 双公网 health/ready 为 200；Chat/Responses 无密钥请求为标准 401 且有 request_id；六家独立 Key 目录为 200。以上均不算生成成功。
- 22:36:32 发起的 Arena Max 公网 Responses 请求 `0a0d8bb3-fad1-42c9-9efd-81b5e365db74`，165.6 秒返回 `response.failed` / `anti_bot_rejected` / `recaptcha_v2_required`。OpenAI SSE 错误契约正常，Arena 尚未恢复。
- 新日志显示清理约 0.6–1.6 秒完成；后台保活/浏览器启动与推理竞争时仍观测到 25–65 秒槽位等待。健康接口在该次样本中正常，不把清理修复等同于全部延迟已消除。

## 遗留与回滚

- 发布时历史账号复核仍被 v3/v2 阻断。后续通过本机 Edge、同出口服务端 Chromium 及历史账号隔离桥接取得完整输出，页面脚本上下文和原生提交状态问题转入 [1.5.8 修复规格](../requirements/ARENA_PAGE_WORLD_RECOVERY_1.5.8.md)；不新增人工验证管理入口。
- 当前准备阻塞的具体协程尚未取得直接栈证据；关闭无超时是源码确认的缺口，不能宣称它是全部挂起的唯一根因。部署后通过新增阶段日志继续判定。
- 代码回滚使用 1.5.4 四应用不可变镜像组合，不回滚账号/代理数据；1.5.3 未进 GitOps且不复用。
