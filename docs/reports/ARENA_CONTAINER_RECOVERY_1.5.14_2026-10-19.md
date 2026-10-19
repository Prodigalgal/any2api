# Arena 容器与 Camoufox 对照（2026-10-19）

## 当前结论

Arena 尚未恢复。生产 1.5.13 的 Chromium 和 Camoufox 都能启动、恢复有效登录并发出官方原生请求，但生成均被官方返回 403 `recaptcha validation failed`；同一账号在本机 Edge 能完成原生聊天。没有因一次引擎切换测试而修改生产账号。

构建链路存在独立的确定缺陷：四个应用 Dockerfile 默认继承同一历史基础镜像，发布流水线没有消费新基础镜像输出；更新应用及锁文件不能保证容器内依赖更新。1.5.14 修复该缺陷，不能在真实生成验收前将其表述为已解决验证码拒绝。

## 原生对照证据（均为北京时间）

| 时间 | 环境 | 身份与原生请求 | 结果 |
| --- | --- | --- | --- |
| 14:41 | 本机 Edge 155 | 当前账号、Max、字面量“你好”，9 个原生字段 | HTTP 200，839 bytes，`a0` 与 `ad`，无错误帧；页面显示回答 |
| 14:45 | 1.5.13 隔离 Chromium 149，有窗口 | 同一账号、Sophia、Max、字面量“你好”，9 个字段，20 字符 cx | HTTP 403，39 bytes，`recaptcha validation failed`，0 正文 |
| 14:55 | 1.5.13 隔离 Camoufox 152.0.4-beta.28，有窗口 | 同一账号、Sophia、Max、字面量“你好”，9 个字段，20 字符 cx | HTTP 403，39 bytes，同一错误，0 正文 |

- 两次服务器对照均使用基础 `OfficialBrowserRuntime`，不加载 Arena 专用页面准备/交互脚本，不使用角色前缀；各允许一条实际生成 POST，均未发生额外 POST。
- Camoufox 包 0.5.4 / Playwright 1.60.0，启动 16.439s、生成 16.613s、总计 38.042s；认证状态 200，账号 subject/email 匹配且已同意条款。使用现有启动配置，没有添加指纹或验证码处理参数。
- Camoufox 结束后浏览器名额 `in_use=0`，代理 `references=0/total=0`，隔离 Job `any2api-arena-diag-20261019065449` 已删除。诊断不写入生产 INFERENCE 台账、不重置初次验证预算。
- 当前容器 UID 10001、`/dev/shm` 1Gi；Camoufox 动态库没有 `not found`，中文字体列表为空。现有 OOM 计数为 0；不能认定当前 403 是内存或共享内存不足。
- 本机与服务器并未完成同时间、同实际出口地址的完整控制；原生网页成功不代表下游 OpenAI 桥接已恢复。

## 故障开始时间与版本边界

对同一历史成功生产账号的实际 `INFERENCE` 回读：

- 03:49:25 请求 `7a89606d-ab42-488b-abe4-93056878ad76` 成功，459 字符正文，耗时 28.896s。
- 03:54:28 请求 `c7e15fb3-a9bb-44b9-8b81-74dbc18cc811` 开始返回 `anti_bot_rejected / recaptcha_v2_required`，正文 0；随后同一账号继续出现相同拒绝。
- 1.5.11 源码于 09:44 提交，1.5.12 于 10:03、1.5.13 于 10:29；故障早于这些版本。03:49 前后没有已确认的部署变化，不能把失败归因于这三次更新，也不能证明更早版本、会话状态、上游判定或网络因素无影响。

## 1.5.14 Changelog

- **容器依赖**：`Dockerfile.browser-runtime` 从冻结锁文件导出生产 requirements；排除应用本身和开发依赖，应用版本变化可复用浏览器层。Patchright 精确锁定 1.64.1，其浏览器清单为 Chromium 156.0.8078.4；Camoufox 保留既有 0.5.4 / 152.0.4-beta.28，并固定已有 ARM64 下载资产 SHA 前缀 `3a105a2f`。
- **构建一致性**：四个应用 Dockerfile 必须显式接收 `BROWSER_RUNTIME_IMAGE`，在安装源码前比较依赖清单；`check_runtime_version.py` 给出明确的不匹配失败，安装后继续验证 Python 依赖和应用/API 版本。
- **流水线**：`Build and deploy` 为每次发布构建当前基础镜像，将 digest 输出传入应用；基础镜像独立入口复用相同质量流程，禁止更新 GitOps。tag 加入 run/attempt 标识，避免重跑覆盖同名制品。
- **运行环境**：补齐中文字体；新增 `check_browser_environment.py`，构建时以 UID 10001 在无窗口与 Xvfb 模式分别启动 Chromium/Camoufox，离线渲染中文/Canvas 并关闭资源，不产生厂家生成请求。
- **兼容**：业务版本统一为 1.5.14；OpenAI、数据库、权限和账号配置不变。原 Self Proxy 四节点、停用厂商、一次入库验证预算保持。

## 验证和发布

- 本地相关回归 74 passed；完整 Automation 701 passed / 3 Redis 条件跳过，Ruff/146 文件格式通过。Backend test 缓存复用、1.5.14 bootJar；Web lint/build/TypeScript 通过。
- 1.5.14 源码/JAR 契约通过，Actionlint 1.7.12 校验两个 workflow 通过；锁文件仅更新应用版本和 Patchright。
- 候选尚未发布生产；后续补齐 ARM64 构建、离线浏览器检查、隔离候选和实际运行验收证据。生产目前维持 1.5.13。
- **遗留与回滚**：官方验证拒绝未解决，无法从该错误直接确定服务端判定理由；应用回滚点为 1.5.13 原不可变镜像组合，保留配额与代理绑定，无数据库回滚。
