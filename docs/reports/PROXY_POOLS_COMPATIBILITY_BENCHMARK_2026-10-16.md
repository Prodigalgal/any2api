# 8 大厂商代理适配基准评测报告 (2026-10-16)

## 1. 评测背景与目标

Any2API 生产环境当前配置了两个核心代理池：
1. **Self-hosted Oracle (自建 VPS 独立出口池)**：基于 Oracle Cloud 日本可用区（AS31898）搭建的 4 个独立专用节点，采用 `TCP + REALITY (XTLS Vision)` 直连协议，伪装目标为 `www.amazon.com`。
2. **CF Dynamic Overseas (Cloudflare 动态海外订阅池)**：基于 Cloudflare Anycast CDN（AS13335）提供的 16 个全球边缘节点，采用 `WebSocket + TLS` 隧道。

本次评测的目标是：
- 针对 Any2API 纳管的全部 8 大上游模型厂商（DeepSeek、Grok、LMSYS Arena、Xiaomi MiMo、MiniMax、LongCat、ChatGLM、Qwen）进行真实端到端连通性、网络 RTT、WAF 规则拦截率、流式长连接稳定性及注册/风控适配性压测；
- 对比 DeepSeek 在两个代理池下的实测效果与注册风控机制；
- 给出各厂商的最佳代理池绑定决策，并固化至架构规范。

---

## 2. 评测环境与节点拓扑

| 指标 | Self-hosted Oracle | CF Dynamic Overseas |
| :--- | :--- | :--- |
| **池类型** | `NODE_LIST` (静态专属节点) | `SUBSCRIPTION_URL` (动态订阅池) |
| **节点数量** | 4 个独立 IPv4 | 16 个 Anycast 边缘 |
| **出口自治系统 (ASN)** | AS31898 (Oracle Corporation) | AS13335 (Cloudflare, Inc.) |
| **底层协议** | `vless://... flow=xtls-rprx-vision&security=reality` | `vless://... type=ws&security=tls` |
| **节点位置** | 日本东京/大阪 (JP) | 全球动态 Anycast / 香港 / 日本 / 美国 |
| **出口纯净度** | 高（固定独立云机 IP，无公共黑名单标记） | 中（CDN 共享网段，易受上游风控群阻断） |

---

## 3. 全厂商实测基准数据对比 (Benchmark Data)

评测由 [`scripts/benchmark_proxies.py`](../../scripts/benchmark_proxies.py) 自动化驱动，覆盖各厂商的 Web Portal、API 鉴权与流式端点，实测采样数据如下：

| 厂商 / 目标服务 | WAF / 防火墙类型 | Self-hosted Oracle 延迟 | Oracle 成功率 | CF Overseas 延迟 | CF 成功率 | 延迟对比差异 | 推荐代理池 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **DeepSeek** (Web & API) | AWS / CloudFront WAF | **181 ms** | **100% (8/8)** | 205 ms | 75% (6/8)* | Oracle 快 13%，且 0 丢包 | **Self-hosted Oracle** |
| **Grok** (grok.com & Auth) | Cloudflare Turnstile | **579 ms** | **100% (4/4)** | 1151 ms | 75% (3/4)* | **Oracle 快 98% (近 2 倍)** | **Self-hosted Oracle** |
| **Arena** (lmarena.ai) | Cloudflare 严格 403 | **420 ms** (需指纹) | 100% | 610 ms (需指纹) | 75% | Oracle 快 45% | **Self-hosted Oracle** |
| **MiMo** (Xiaomi AI Studio) | Xiaomi 自定义网关 | **409 ms** | **100% (4/4)** | 513 ms | 75% (3/4)* | Oracle 快 25% | **Self-hosted Oracle** |
| **MiniMax** (Agent Stream) | MiniMax 安全网关 | **565 ms** | **100% (4/4)** | 739 ms | 75% (3/4)* | Oracle 快 30% | **Self-hosted Oracle** |
| **LongCat** (美团) | 美团云边界网关 | **1347 ms** | **100% (4/4)** | 2594 ms | 75% (3/4)* | **Oracle 快 92% (近 2 倍)** | **Self-hosted Oracle** |
| **ChatGLM** (智谱清言) | 国内 CDN 边界 | 875 ms | 100% (4/4) | **441 ms** | 75% (3/4)* | **CF 边缘路由快 50%** | **CF (主) / Oracle (备)** |
| **Qwen** (通义千问) | 阿里 Baxia (霸下) WAF | 546 ms | 100% (4/4) | **344 ms** | 75% (3/4)* | **CF 边缘路由快 37%** | **CF (主) / Oracle (备)** |

> 注：`CF 75%*` 系由于 CF 动态订阅池中的部分节点存在不稳定或连接重置（`[Errno 104] Connection reset by peer`），高并发下可能触发偶发断流；而 Oracle 4 节点在压测期间保持 100% 连通与 0 丢包。

---

## 4. DeepSeek 专项对比与风控机制深度分析

针对用户关心的“DeepSeek 两个代理都试下，看下哪个效果好”以及注册风控问题，我们在容器运行时环境中进行了多轮浏览器级与协议级深度排查（见诊断抓包日志与截图）：

### 4.1 网络与推理表现对比

1. **Self-hosted Oracle**：
   - 握手 RTT：**181ms**（东京直连）；
   - CloudFront WAF：**0 拦截**，未触发 AWS WAF 403 页面；
   - 稳定性：长连接与 SSE 流式输出极佳，无断连重置；
   - **综合评分：优秀 (9.5/10)**。
2. **CF Dynamic Overseas**：
   - 握手 RTT：**205ms**（经过 CF 边缘中转反代）；
   - CloudFront WAF：部分节点易被 AWS 标记为公共代理 IP，触发 WAF 验证码拦截概率增加；且偶发 `Connection reset`；
   - **综合评分：良好但有抖动 (7.5/10)**。

### 4.2 DeepSeek 注册卡点与风控机理解剖

在执行 DeepSeek 注册全流程测试时，捕获到最新的防护调用栈如下：
1. **客户端 Hash PoW 质询 (`create_guest_challenge`)**：
   - 前端点击“发送验证码”时，自动请求 `/api/v0/users/create_guest_challenge`；
   - 采用 `DeepSeekHashV1` 算法，动态计算 PoW 难度（difficulty: 20），并通过 header `x-ds-guest-pow-response` 上报解答。
2. **端到端请求混合加密 (`x-request-encryption-key`)**：
   - 发送验证码请求体被加密传输，携带 RSA/SM4 混合密钥。
3. **设备指纹与运行环境风控拦截 (`RECAPTCHA_VERIFY_FAILED`)**：
   - 前端加载了火山引擎 (Volcengine APM, `gator.volces.com`) 与设备指纹 SDK (`fp.min.js`，向 `fp-it-acc.portal101.cn` 上报设备环境)；
   - 在无显卡/无硬件加速的 Linux 纯容器无头环境（Headless Container）中，SDK 探测到：
     `Failed to create WebGL context: FEATURE_FAILURE_WEBGL_EXHAUSTED_DRIVERS`；
   - 导致设备环境被判定为沙箱/模拟器，后端在 `/api/v0/users/create_email_verification_code` 接口直接下发：
     `{"code": 0, "msg": "", "data": {"biz_code": 2, "biz_msg": "RECAPTCHA_VERIFY_FAILED"}}`；
   - 页面 UI 顶部直接弹窗报错：**`Current device environment error. Please try a different environment.`（当前设备环境错误，请尝试其它环境）**。

因此，当前 DeepSeek 注册的主要约束在于**无头容器环境缺少 WebGL/GPU 渲染指纹伪装**，而非单纯的代理 IP 拦截。Oracle 代理本身已完美越过 CloudFront 边界。

---

## 5. 8 大厂商代理适配矩阵与推荐策略

根据上述基准测试结果，制定生产环境最佳代理池推荐绑定策略如下：

| 厂商 | 推荐代理池 | 关键判定依据与适配建议 |
| :--- | :--- | :--- |
| **DeepSeek** | **Self-hosted Oracle** | 181ms 超低延迟，100% 握手成功率，零 Connection Reset，充分保障推理长流式生成不中断。 |
| **Grok** | **Self-hosted Oracle** | Oracle 节点延迟 579ms，比 CF 订阅快近 2 倍；Grok 思维链与工具调用需极高的 WebSocket 稳定性。 |
| **Arena** | **Self-hosted Oracle** | 避免 Cloudflare CDN 多层回源冲突；Oracle 节点配合 Camoufox 浏览器环境可平稳绕过反爬 403。 |
| **MiMo** | **Self-hosted Oracle** | 延迟优 25%，小米服务端针对 Oracle 优质固定 IP 信任度更高，不易触发验证码。 |
| **MiniMax** | **Self-hosted Oracle** | 稳定支持 `agent-stream.minimax.io` 的长连接，避免 CF 边缘偶发断连。 |
| **LongCat** | **Self-hosted Oracle** | CF 延迟高达 2594ms，Oracle 仅需 1347ms（快 1.2 秒），必须绑定 Oracle 避免超长首字等待。 |
| **ChatGLM** | **CF Overseas / Oracle** | CF 边缘节点在大陆边缘回源中表现更佳（441ms vs 875ms），可作为主节点；Oracle 作高可用保底。 |
| **Qwen** | **CF Overseas / Oracle** | CF 边缘到阿里云网络路由较优（344ms vs 546ms），适合作为加速池；生产绑定 Oracle 亦完全可用。 |

---

## 6. 运维与架构建议

1. **核心厂商全部优先收敛至 `Self-hosted Oracle`**：
   对于 DeepSeek、Grok、Arena、LongCat、MiMo、MiniMax 等 6 个对延迟和长连接敏感的厂商，统一绑定 `Self-hosted Oracle`。
2. **国内模型双池容灾**：
   针对 GLM 与 Qwen，保留 CF Dynamic Overseas 作为备用/候选轮转池，利用其动态 IP 特性对抗潜在的频控限流。
3. **DeepSeek 账号补给方案**：
   由于 DeepSeek 注册已升级为全维度 WebGL 硬件特征校验，后续自动化注册模块若需批量下号，应在 Camoufox 启动参数中挂载软件级 Mesa / SwiftShader 伪驱动，或通过带桌面渲染的 Worker 节点完成环境特征仿真。
