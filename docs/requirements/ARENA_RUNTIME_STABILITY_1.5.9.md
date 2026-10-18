# Arena 会话恢复、原始错误与浏览器稳定性修复（1.5.9）

## 目标与边界

用户要求继续解决 Arena 未恢复、排队与共享 Automation OOM。沿用已授权的修复、发布和生产验收；Arena 保持 Self Proxy 原四节点，DeepSeek/Qwen 继续停用，不恢复周期生成探活，不新增人工验证管理 API/权限。

## 定位与实现计划

1. 区分官方原始错误与桥接推断。现有代码将 429 / `prompt failed` 转为 `prompt_rate_limit`，v2 超时后覆盖原始错误；保留官方状态、正文和可用重试信息，验证分类依据，不将自定义标签当作已证实根因。
2. 核对恢复前后 Cookie 元数据与原生/规范请求差异，不打印 Token/Cookie/私密正文。Arena 保存的 browser storage state 与旧扁平 Cookie 同时存在时，旧 Cookie 只能补齐缺失项，不能覆盖当前适用的已恢复 Cookie；其他厂商维持既有优先级。
3. 核对实际进程内存、cgroup 峰值与生命周期任务。按共享实例的 3Gi 内存预算约束浏览器并发；已被近期有效推理证明健康的账号延后冗余 keepalive，保留初次验证、恢复、临近过期、每日签到和失败处理。
4. 以必要的合成回归、零生成诊断和少量有效输出验收推进。若官方确实要求人工验证，保留明确失败，不伪造或复用验证 Token，不用反复换账号/出口试探代替修复。

## 影响范围

- Automation：`providers/arena_browser.py`、`providers/page_fetch_browser.py` 及回归。
- Backend：`lifecycle/LifecycleScheduler.java`、相关查询及回归（仅在运行证据支持时调整）。
- 部署：`deploy/gitops/automation-runtime-resources.yaml` 的共享实例资源/并发配置及对应验收。
- 统一版本文件和发布报告。无外部 API、数据库结构或权限契约变更；应用和运营数据分别可回滚。

## 验收

- 旧 Cookie 不覆盖 Arena 已恢复的有效作用域 Cookie，缺失/过期项仍能兼容；其他厂商行为保留。
- 原始上游失败原因可追踪，验证码、真正限流和传输超时可分辨；OpenAI 错误/流式结束语义保留。
- keepalive 延后只引用已有成功推理，不新增生成；失败、初次验证、过期及非 keepalive 不被跳过；租约、重试和幂等仍有效。
- 相关回归、全量构建、版本契约、CI、不可变镜像、GitOps、Pod/digest/API 验证通过。最后单独判断 Chat/Responses 是否有正文和正常结束，健康检查不代替生成验收。
- 源码候选 1.5.9 只占用一次；若验收后继续改可部署源码，继续升版本。回滚使用 1.5.8 不可变制品，保留原 Self Proxy 绑定。
