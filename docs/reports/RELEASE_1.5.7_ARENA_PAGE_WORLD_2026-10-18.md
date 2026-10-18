# Arena 页面上下文与原生提交修复（1.5.7）

时间均为 UTC+8。对应 [修复规格](../requirements/ARENA_PAGE_WORLD_RECOVERY_1.5.7.md)。

## 定位证据

| 对照 | 结果与边界 |
| --- | --- |
| 本机 Edge，HY2 Sophia IPv6 | Max “你好”完整回答；账号状态只在受限 private 文件和诊断内存中使用 |
| 服务端 Chromium headful，同账号/同 IPv6 | 原生提交 200，网页历史独立确认完整回答；服务端 Linux 有可用环境 |
| 1.5.5 Patchright 原桥接 | 隔离脚本世界看不到主页面 SDK，v3 unavailable，未发生成；Patchright 1.61.2 默认 `isolated_context=True` 与现场一致 |
| 仅修主世界 | SDK/cx 可读，一次生成仍为 403/v2；不能仅凭脚本可读判定恢复 |
| 主世界 + 本次原生 UI Token/cx | 隔离桥接 23 字正文、`ad` 正常结束、200、无流错误；一次真实生成 |
| 历史服务器账号，1.5.6 候选 | 首次完整回答，第二次 UI 准备超时且未发生成；候选不发布、不复用版本号 |
| 同历史账号，1.5.7 候选 | 首次 22 字正文 + `ad`；第二次正常发出但上游 429/验证升级，未确认连续两次成功；两次仅创建一个浏览器 |

这些对照支持修复浏览器执行环境与提交路径。并未证明所有 IP、账号或浏览器引擎都可用；429 与官方交互式验证仍需独立判定。

## Changelog

### 页面脚本：`official_browser.py`、`page_fetch_browser.py`

- `evaluate_in_page_world` 只对 Patchright Page 传 `isolated_context=False`，普通 Playwright/Camoufox 保持既有签名；不通过捕获 TypeError 重试脚本，避免请求副作用重复。
- 页面 fetch、Arena SDK/模块导出、上传及取消 controller 使用一致页面世界。
- `prepare_stream_request` 是账号会话所有权内的内部 hook，默认保留原 `before_stream_request` 行为，不改变其他厂商请求结构或外部接口。

### Arena 提交：`arena_browser.py`

- 以当前规范正文准备官方 UI 提交，拦截准备请求并本地结束，不向上游生成；只提取本次 `cx` 和 v3 Token，随后实际桥接请求使用原模型、完整正文和上传附件。
- 准备阶段 30 秒预算，缺少字段失败，取消/异常移除临时 route。Token 不写入凭据、日志或会话缓存；v2 重试只使用新 v2 Token，不夹带原 v3。
- 使用过的页面在下一次准备前回到新草稿，保留浏览器、Context、代理和已有资源生命周期；WeakSet 不持有已关闭页面。纯附件准备使用内部占位草稿，实际发出的原空正文及附件保持。
- 生成时长使用已有 `inference_timeout_seconds`（默认 240），目录/账号读取继续使用 canary 规则，防止生成被目录的 60 秒上限截断。

### 兼容性与测试

- Backend/Web/Automation 与锁文件统一 1.5.7。无 API、权限、数据库迁移；DeepSeek/Qwen 仍停用，不恢复周期生成探活或模型可用性判定。
- 新增脚本世界签名/副作用、原生准备缺字段/超时/取消、连续准备重置、附件与正文保留、原生 Token 不被覆盖、v2 兼容、内部 hook 失败不 fetch、推理 deadline 回归。
- 1.5.7 本地全量 655 passed（含真实 Redis），Ruff check/138 文件 format check、Backend `test`（缓存有效）/新 bootJar、Web ESLint/生产构建/TypeScript、源码与 JAR 版本契约通过。保留一项既有 Starlette/httpx 弃用警告；CI 和运行态另行回写。
- 1.5.6 本地门禁通过，但因连续请求现场缺陷未发布，版本占用不回收。

## IPv6 池与生产验收

- 固定 IPv6 服务四节点的 `api64.ipify.org` 检查均 200，实际地址与各节点配置逐一一致，单次约 1.8–3.3 秒；只证明代理连通和出口固定。
- 节点 1 在修复后的 Chromium 环境、历史服务器账号下有 23 字正文、`ad` 完成、200，无 v2，约 28.2 秒。其余节点正在逐一验证，不能扩展为全池已恢复。生产仍为 1.5.5；本轮尚未更新账号/代理数据。

## 风险与回滚

- 官方 UI/页面模块会变化，准备失败须明确结束，不能静默复用旧 Token 或假定验证已通过。
- 目前尚未取得全部 IPv6 节点、全部账号、工具/多模态的现场恢复验收；正文/附件保留有回归测试，不能替代多模态实测。
- 回滚使用 1.5.5 四应用不可变镜像；若后续修改账号/池绑定，另行记录受限备份与回滚方法。现有 IPv6 服务独立保留。
