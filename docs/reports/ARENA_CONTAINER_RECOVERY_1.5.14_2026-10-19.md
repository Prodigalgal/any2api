# Arena 容器与 Camoufox 对照（1.5.14～1.5.16，2026-10-19）

## 当前结论

Arena 尚未恢复。1.5.13 与补齐依赖的 1.5.14 隔离容器都能启动、恢复有效登录并发出官方原生请求，但 Chromium/Camoufox 生成均被官方返回 403 `recaptcha validation failed`；同一账号在本机 Edge 能完成原生聊天。16:13 已确认生产提升为 1.5.16，其他五家限次验收取得完整输出；没有因引擎切换测试而修改生产账号。

构建链路存在独立的确定缺陷：四个应用 Dockerfile 默认继承同一历史基础镜像，发布流水线没有消费新基础镜像输出；更新应用及锁文件不能保证容器内依赖更新。1.5.14 修复该缺陷，不能在真实生成验收前将其表述为已解决验证码拒绝。

## 原生对照证据（均为北京时间）

| 时间 | 环境 | 身份与原生请求 | 结果 |
| --- | --- | --- | --- |
| 14:41 | 本机 Edge 155 | 当前账号、Max、字面量“你好”，9 个原生字段 | HTTP 200，839 bytes，`a0` 与 `ad`，无错误帧；页面显示回答 |
| 14:45 | 1.5.13 隔离 Chromium 149，有窗口 | 同一账号、Sophia、Max、字面量“你好”，9 个字段，20 字符 cx | HTTP 403，39 bytes，`recaptcha validation failed`，0 正文 |
| 14:55 | 1.5.13 隔离 Camoufox 152.0.4-beta.28，有窗口 | 同一账号、Sophia、Max、字面量“你好”，9 个字段，20 字符 cx | HTTP 403，39 bytes，同一错误，0 正文 |
| 15:44 | 1.5.14 隔离 Chromium 156，有窗口 | 同账号/出口、有效认证、官方 UI 本次生成的 v3/cx、9 个原生字段 | HTTP 403，39 bytes，同一错误，生成 5.391s |
| 15:45 | 1.5.14 隔离 Camoufox 152.0.4-beta.28，有窗口 | 同上，中文字体/EGL/GLES 已补齐 | HTTP 403，39 bytes，同一错误，生成 14.785s |

- 两次服务器对照均使用基础 `OfficialBrowserRuntime`，不加载 Arena 专用页面准备/交互脚本，不使用角色前缀；各允许一条实际生成 POST，均未发生额外 POST。
- Camoufox 包 0.5.4 / Playwright 1.60.0，启动 16.439s、生成 16.613s、总计 38.042s；认证状态 200，账号 subject/email 匹配且已同意条款。使用现有启动配置，没有添加指纹或验证码处理参数。
- Camoufox 结束后浏览器名额 `in_use=0`，代理 `references=0/total=0`，隔离 Job `any2api-arena-diag-20261019065449` 已删除。诊断不写入生产 INFERENCE 台账、不重置初次验证预算。
- 当前容器 UID 10001、`/dev/shm` 1Gi；Camoufox 动态库没有 `not found`，中文字体列表为空。现有 OOM 计数为 0；不能认定当前 403 是内存或共享内存不足。
- 15:07 邻近时点从 Edge 与固定 Sophia 隔离容器只读请求 Arena 的 `/cdn-cgi/trace`，出口地址 SHA-256 摘要一致，均为 IPv6 / KIX；未记录明文出口地址。该查询未产生生成请求，临时 Job 已删除；仍不能代表此前每个历史请求的实际网络状态。

## 故障开始时间与版本边界

对同一历史成功生产账号的实际 `INFERENCE` 回读：

- 03:49:25 请求 `7a89606d-ab42-488b-abe4-93056878ad76` 成功，459 字符正文，耗时 28.896s。
- 03:54:28 请求 `c7e15fb3-a9bb-44b9-8b81-74dbc18cc811` 开始返回 `anti_bot_rejected / recaptcha_v2_required`，正文 0；随后同一账号继续出现相同拒绝。
- 1.5.11 源码于 09:44 提交，1.5.12 于 10:03、1.5.13 于 10:29；故障早于这些版本。03:49 前后没有已确认的部署变化，不能把失败归因于这三次更新，也不能证明更早版本、会话状态、上游判定或网络因素无影响。

## 1.5.14 Changelog

- **容器依赖**：`Dockerfile.browser-runtime` 从冻结锁文件导出生产 requirements；排除应用本身和开发依赖，应用版本变化可复用浏览器层。Patchright 精确锁定 1.64.1，其浏览器清单为 Chromium 156.0.8078.4；Camoufox 保留既有 0.5.4 / 152.0.4-beta.28，并固定已有 ARM64 下载资产 SHA 前缀 `3a105a2f`。
- **构建一致性**：四个应用 Dockerfile 必须显式接收 `BROWSER_RUNTIME_IMAGE`，在安装源码前比较依赖清单；`check_runtime_version.py` 给出明确的不匹配失败，安装后继续验证 Python 依赖和应用/API 版本。
- **流水线**：`Build and deploy` 为每次发布构建当前基础镜像，将 digest 输出传入应用；基础镜像独立入口复用相同质量流程，禁止更新 GitOps。tag 加入 run/attempt 标识，避免重跑覆盖同名制品。
- **运行环境**：补齐中文字体和 EGL/GLES 标准库；新增 `check_browser_environment.py`，构建时以 UID 10001 在无窗口与 Xvfb 模式分别启动 Chromium/Camoufox，离线渲染中文/Canvas、记录 WebGL 结果并关闭资源，不产生厂家生成请求。
- **兼容**：业务版本统一为 1.5.14；OpenAI、数据库、权限和账号配置不变。原 Self Proxy 四节点、停用厂商、一次入库验证预算保持。

## 验证和发布

- 本地相关回归 74 passed；完整 Automation 701 passed / 3 Redis 条件跳过，Ruff/146 文件格式通过。Backend test 缓存复用、1.5.14 bootJar；Web lint/build/TypeScript 通过。
- 1.5.14 源码/JAR 契约通过，Actionlint 1.7.12 校验两个 workflow 通过；锁文件仅更新应用版本和 Patchright。
- 候选尚未发布生产；后续补齐 ARM64 构建、离线浏览器检查、隔离候选和实际运行验收证据。生产目前维持 1.5.13。
- **遗留与回滚**：官方验证拒绝未解决，无法从该错误直接确定服务端判定理由；应用回滚点为 1.5.13 原不可变镜像组合，保留配额与代理绑定，无数据库回滚。

## 三组容器的环境设计与基线模拟

- 同一不可变镜像、Linux/aarch64、UID 10001，固定相同 Xvfb 启动命令；每组 CPU limit 600m、内存 2Gi、shm 512Mi。禁止向非 loopback 地址发浏览器请求，使用纯合成账号及验证字段，无生产凭据、Service 或服务账号 Token。
- Chromium 两组使用浏览器默认环境，不恢复旧 UA/运行指纹。Camoufox 使用现有库生成的 Linux 测试配置、关闭测试不需要的行为模拟和 GeoIP 网络查询；其 screen/GPU 信息只视为库生成值，不视为宿主硬件事实，未将其复制到生产账号。
- 1.5.13 基线三组各完成 12 个场景，共 **36/36 通过**。覆盖 UTF-8 跨网络分块、CRLF/末行无换行、连续复用、同账号并发、403/429、合成交互验证失败、超时、取消、超限帧及失败后恢复。
- 每组仅创建 1 个浏览器，12 次准备产生 12 个独立合成上下文；关闭后浏览器预算/页内 controller/widget/流队列/连接/浏览器进程均归零，3 个 Job 已删除。模拟不证明 Arena 或 OpenAI 真实业务恢复。

| 基线组 | 版本 | 语言/时区 | 实际页面尺寸 | WebGL | 12 场景总耗时 |
| --- | --- | --- | --- | --- | --- |
| Chromium 无窗口 | 149.0.7827.0 | en-US@posix / UTC | 1280×720 | SwiftShader，可用 | 11.021s |
| Chromium 有窗口 | 149.0.7827.0 | en-US / UTC | 1280×720 | 不可用 | 13.781s |
| Camoufox 有窗口 | 152.0.4-beta.28 | en-US / UTC | 1920×927 | 库报告 NVIDIA 值，可用 | 43.680s |

这张表记录实际读数，未将不同引擎生成的属性宣称为完全相同的环境。系统检查确认 EGL/GLES 库缺失；新增标准库后须复测 WebGL，不能仅凭安装完成推定图形恢复或验证码通过。

## 构建候选迭代记录

- `781a23a` / CI `39213844384`：三端质量通过，ARM64 基础镜像的非 root、15 组中文字体、四项浏览器启动检查通过；应用构建被空的基础镜像引用阻断，未部署。CI 明确报告输出 `image` 可能包含 secret 而被清空。
- `3103a44`：改为只跨 Job 传递 digest、在消费端拼接公开镜像路径，并校验 digest 格式；CI `39215203194` 在追加 EGL/GLES 修复后主动取消，未部署。
- `14577b0` / CI `39215504428`：补齐 EGL/GLES 及构建校验，完整 CI/六组件构建通过；Automation 704 passed，基础镜像四项离线检查通过，中文字体 15 组。生产保持 1.5.13，所有 tag 含源码/run/attempt，未覆盖已有制品。
- 03:40～04:15 的同账号 `operation_events` 查询没有记录到生命周期操作；不能证明此时绝无其他状态变化，但目前没有证据指向一次 keepalive/refresh 导致首个拒绝。

## 1.5.14 集群结果与 1.5.15 镜像层修复

- 首次三容器仍在拉取镜像时触发旧诊断 180 秒等待上限，未执行测试，临时 Job 全部清理；将诊断等待扩大到有界 600 秒后继续，未更改生产探针。
- 第二轮两组 Chromium 156 各 12 场景通过；Camoufox 走完 12 场景后在末尾总量断言失败。该轮未保存失败断言的具体计数，不能直接认定重复生成或浏览器重建。单独追加 Camoufox 复测：12 场景通过，浏览器创建 1、POST/准备/唯一合成上下文各 12、所有资源回收；保留原异常，等待最终候选三组复核。
- 标准 EGL/GLES 库和中文字体已存在；有窗口 Chromium 的 WebGL 仍为不可用，无窗口为 SwiftShader。补齐依赖未自动修复该图形差异，未设置绕过图形安全限制的参数，也未声称已修复官方验证。
- Registry ARM64 manifest 显示 1.5.14 Arena 应用压缩镜像约 **3710MiB**，最大两层各约 **1720MiB**。依赖/浏览器安装后递归 `chown` 将相同文件复制进另一镜像层，放大分发与冷启动成本。
- **1.5.15 变更**：先创建 UID 10001 和可写目录，以运行用户安装依赖及浏览器，删除递归 chown；系统库仍由 root 安装，最终运行/构建 smoke 仍为非 root。新增所有权顺序和禁止递归层复制的回归，其他厂商/协议/账号配置保持。
- 1.5.14 已进入隔离集群，版本号保留；新源码 `0091453` / CI `39217114688` 使用 1.5.15 全组件版本。相关 9 项回归、Ruff、1.5.15 源码/JAR 契约通过；等待镜像大小、三组模拟及真实运行验收后发布。生产仍为 1.5.13。

## 0.x 历史环境核对

- 用户补充 0.x 容器曾可用，故直接读取已部署 0.30.0 的源码 `516002ab`、GitOps `13f0d0d` 和 Registry 不可变镜像，另核对 0.30.1 源码 `a766ead`。
- 0.30.0、0.30.1、1.5.13 的 `automation/Dockerfile.arena` 内容 SHA-256 完全相同，均固定继承基础镜像 digest `22954d16b8bd1b06619289825b320b751f6d11335c6ec9665af0453982a128c2`。0.30.0 原发布流水线没有传入其他基础镜像参数。
- Registry 中实际 0.30.0 与 1.5.13 的 ARM64 镜像各有 14 层，前 11 层 digest 完全一致；运行用户均为 10001，entrypoint/CMD 相同，Xvfb 仍为 1365×900×24。不能把 1.x 升级描述为更换了基础操作系统或启动方式。
- 确实有变化的是应用层：代理引用与清理所有权、页面主执行上下文、每次原生请求准备/草稿重置、Cookie 恢复优先级、并发/取消边界。资源方面后续增加了内存和临时存储，并非减少；原 headless 默认和基础 Deployment/ConfigMap 没有对应改动。
- 这些应用修改并不自动等于故障原因：1.5.10 部署时段内 03:49 仍有完整输出，03:54 同账号开始拒绝；当前去掉 Arena 专用准备/桥接逻辑，原生 UI 在服务器仍被拒绝。旧版本直接回滚能否恢复尚未验证，不能把历史可用性外推为今天必然可用，也不能把“上游策略变了”当作已证实根因。
- 旧事件缺少当前 `eventType` 判别字段，但仍保存 `delta` 与 `finishReason`。按旧结构回读 0.30.0 部署后五条 `success`：都有非空文本与结束记录；其中 10 月 12 日 **23:52:33** 请求 `11276dea-9fe8-4c05-845a-3bb467accfe2` 有 **6056 字符**、133.929s。用户所说“0.x 容器能通过”有实际台账支持，不能因为当前格式统计不到就否定历史成功。仅提取元数据，没有读取/输出正文，也没有将旧应用连接到生产数据库或追加旧镜像生成请求。
- 本轮历史比较产生 **0 条厂家生成请求**；原始镜像/源码比较元数据位于忽略的 `backend/build/arena-0x-comparison-20261019.json`。

## 1.5.15～1.5.16 最终候选 Changelog

- **镜像分发**：1.5.15 CI `39217114688` 全通过（Automation 705 passed），Arena 压缩镜像从约 3710MiB 降至 1989.1MiB，约减少 46%；三组隔离模拟 36/36 通过。修复递归 chown 的重复文件层，没有删浏览器功能换取大小。
- **保存环境兼容**：1.5.16 的 `official_browser.context_options` 使用实际浏览器主版本与 headless 模式校验保存 UA；不匹配时恢复浏览器默认值，保留 Cookie/语言/时区/尺寸。GLM 两条同步调用传入实际版本，MiniMax 复用同一映射，只有 UA 兼容时才保留其旧环境注入；Camoufox 的既有配置不变。
- **测试与版本**：新增过时 UA、跨引擎/跨模式、同版本兼容和 Camoufox 保留回归。完整 CI `39218673239` 成功：Automation **720 passed**，Backend test/1.5.16 bootJar、Web lint/build/TypeScript、Ruff、版本契约和所有 ARM64 镜像构建通过。本地完整 Automation **717 passed / 3 Redis 条件跳过**；没有把本地跳过项计为通过。
- **镜像定位**：应用源码 `cf044d08b8d37466f5bf1fe175297cd3efe4011d`，不可变 suffix `20261019-v1.5.16-release-cf044d08b8d37466f5bf1fe175297cd3efe4011d-run39218673239-1`。基础镜像 index digest 为 `38b7e2f095872601d2c3929e434cc023013d6cb8a77c7eff1dadb1604dc4a165`，Arena 应用 index digest 为 `f3c3471bed469bcbe2a7122c2a79ba86560053c96ca24419c3d08df41d83bc70`。
- **本地构建兼容**：`docs/DEVELOPMENT.md` 补充 Compose 显式传入匹配的 `BROWSER_RUNTIME_IMAGE` 后 `up --no-build`；普通仅启动 PostgreSQL/Redis 的命令保持。没有修改 OpenAI、数据库、权限和账号契约。

## 最终三组隔离模拟与异常复核

| 最终 1.5.16 组 | 场景 | 耗时 | 保存 UA 兼容验证 |
| --- | --- | --- | --- |
| Chromium 156 无窗口 | 12/12 通过 | 8.211s | 保存的 HeadlessChrome 149 被忽略，实际版本/模式匹配 |
| Chromium 156 有窗口 | 12/12 通过 | 12.427s | 保存的 HeadlessChrome 149 被忽略，实际版本/模式匹配 |
| Camoufox 152 有窗口 | 12/12 通过 | 41.106s | 保留既有 Camoufox 测试配置 |

- 最终候选 **36/36 通过**，每组只创建一个浏览器；结束时页内 controllers/widgets、浏览器进程、连接、预算使用和等待队列均为 0；三个专属 Job 全部删除。三组均产生 0 条真实厂家生成请求。
- 复核此前 Camoufox 末尾断言失败：700ms 的超时用例可以在 POST 发出前取消，合法的实际网络 POST 总数应为 11 或 12，准备仍须 12 次；此前硬编码“恰好 12 POST”的总量断言过强。诊断改为逐 canonical request ID 验证：普通用例恰好一个 POST，超时允许 0 或 1；所有已发送请求与合成上下文唯一。最终不再将该合法取消误记为漏发/重复生成。
- 有窗口 Chromium WebGL 仍不可用，无窗口使用 SwiftShader；补 EGL/GLES 不等于消除图形差异。Camoufox 的 NVIDIA 等值属于库生成配置，并非服务器物理 GPU，未复制到生产账号。
- 1.5.14 的两次真实原生请求仍被官方 403 拒绝。1.5.16 UA 修复没有改变使用默认环境的原生对照路径，因此没有继续对 Arena 进行同条件重复生成；Arena 恢复门槛保持“实际正文与完成帧”。

## 生产提升与回滚边界

- GitOps `f65dc8ccaf167e86b4868183f2e8688e431bfe71` 只提升五个镜像 tag，复用已验收制品，不重新构建或覆盖 tag。Kustomize 23 个对象渲染通过；内存/CPU 配额、普通 Read 超时、公开流路由、300 秒管理验证路由、DeepSeek/Qwen 停用保持。
- 16:08 推送、16:13 复核完成：Argo `Synced/Healthy`，四应用实际 1.5.16、Ready、零重启，registry index/ARM64 与运行 imageID 一致。旧共享 Pod 退出时出现一次 readiness connection refused，浏览器节点冷拉取期间新 Pod 暂未 Ready；没有将该滚动窗口宣称为无中断发布，后续恢复后再进行业务验收。
- 无数据库迁移、无账号状态/初检预算重置、无代理池或引擎强制切换。1.5.14/1.5.15 是保留版本占用的隔离候选，未发布生产。
- 如需回滚，使用原 1.5.13 的不可变镜像组合并保留现有配额/代理绑定；浏览器会从 156 回退 149，须核对新保存 UA 与旧引擎的兼容性，不能将应用镜像回退视为会话状态自动回退。已通过模拟的 1.5.15 可提供同浏览器版本的隔离定位基线，但未经生产完整验收，不自动替代回滚方案。

### 生产版本与公开契约

| 组件 | 生产 Pod | 实际版本 | 状态 |
| --- | --- | --- | --- |
| Backend | `any2api-server-77d464856d-krpz5` | 1.5.16 | Ready / 0 restarts |
| 共享 Automation | `any2api-automation-7675f5678f-sxhz5` | 1.5.16 | Ready / 0 restarts |
| Arena Automation | `any2api-automation-arena-7558ddb8d6-xcn58` | 1.5.16 | Ready / 0 restarts |
| Web | `any2api-web-6dd6f5df77-8mltc` | 1.5.16 | Ready / 0 restarts |

- `any2api.mnnu.eu.org` 与 `any2api-direct.mnnu.eu.org` 的 healthz/readyz 均 200；Chat/Responses 无凭据请求均为含 request_id 的 OpenAI 401 错误；六家独立 key 的模型目录均 200 且无跨厂商模型。以上检查 **0 条生成请求**，Arena 目录成功不代表推理成功。
- 16:15 短时资源读数：共享 Automation 1175MiB/6Gi、Arena 1025MiB/6Gi；共享 CPU 1883m/2 cores。该窗口 CPU 较高，不能由单点读数断言无性能瓶颈或长期稳定。

### 五家生产限次 Responses 验收

| 厂商/模型 | request_id | 正文字符 | 耗时 | 结束事件 |
| --- | --- | --- | --- | --- |
| MiMo / mimo-v2.6-pro | `84e6c8a8-64c1-4214-a05a-bbdf9390d91a` | 4 | 8.867s | response.completed |
| MiniMax / MiniMax-M2.7 | `392c601e-aea9-4f62-9aa6-8587ffa0bfb3` | 4 | 8.800s | response.completed |
| Grok / grok-chat-fast | `364f45e3-fcac-49b0-a485-48e56ebd410b` | 5 | 35.664s | response.completed |
| GLM / GLM-5-Turbo | `bcdc86d4-cf46-49a8-9ea6-71308b015351` | 4 | 66.563s | response.completed |
| LongCat / longcat-flash | `6c8d3f64-cad1-4f6c-aadd-50f36f041f8e` | 4 | 7.019s | response.completed |

- 每家仅一个客户端请求，最多两个并行，无定时生成探测；未记录回答正文和 API key。请求使用短合成输入，不能代表长上下文/工具/多模态或全部模型已验收，也不能将单次耗时解释为本次性能改善。
- Arena 继续使用已完成的原生对照结果，未在发布后追加同条件消耗；DeepSeek/Qwen 仍为 0 replicas。所有本轮模拟/诊断 Job 已删除。
- 16:18 只读对账：六家三种流量作用域仍绑定原 Self Proxy 四节点池；导入 Edge 账号仍 PENDING/disabled，其 INITIAL 检查仍是 10:22:42 完成的 `FAILED / InitialVerificationInterrupted`，未重置或重试；历史成功账号仍 DEGRADED/enabled。过期 LEASED 维护任务为 0，不能据此推定 Arena 业务可用。

## 结论与下一步边界

- 已修复并发布的是可确认的依赖构建、镜像层和旧环境恢复缺陷；用户的 0.x 历史成功已经得到台账与不可变镜像证据支持。
- 仍未定位的是官方验证拒绝的具体内部因子；相同基础容器曾可用、同版 1.5.10 内先成功再失败、同出口 Edge 成功而服务器原生失败，这三项必须同时保留，不能只挑一项宣称根因。
- 下一步若需进一步定位，应围绕官方支持的交互验证与服务器长期会话状态取证，并维持限次验收；不要重复同条件请求、盲目回退全系统或更换出口来替代证据。现有公开 OpenAI 契约和其他五家桥接继续运行，Arena 403 保持明确错误。
