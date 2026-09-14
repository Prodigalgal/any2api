# 全厂商全链路打通任务规格

## 目标

为业务范围内的 Arena、DeepSeek、GLM、LongCat、MiMo、MiniMax、Qwen 打通完整链路：
注册（Runtime-only）、重新认证、保活、打卡（仅 MiniMax）、模型发现、文本双通道、
多模态双通道（厂商已声明能力处），并形成可复现的 K8S 真实验收证据。

Grok / Grok Console / Grok Web 不在本轮业务范围。

## 范围

- 生命周期：`register` / `reauthenticate` / `keepalive` / `daily_checkin`（Runtime-only）
- 推理：`chat` / `model_discovery` 的 Runtime 与 API 双通道
- 多模态：图片；文件（仅声明 `FILE_INPUT` 的 LongCat、Arena）
- 模式策略：KEY 级 `transportMode`；`AUTO` 为 API 优先并可按分类回退 Runtime
- 环境：先 `any2api-e2e-20260817`，证据闭环后再同步生产 `any2api`

## 非目标

- 不绕过验证码、风控、额度和每日打卡限制
- 不把生命周期动作迁移到 API
- 不把未通过真实验收的模型/能力标为 Ready
- 不处理 Grok 三通道

## 全链路定义（每家验收清单）

| # | 用例 | 通道 | 验收标准 |
|---|---|---|---|
| 1 | register | Runtime | 账号落库 `ACTIVE/enabled`，可租约 |
| 2 | keepalive | Runtime | `SUCCEEDED`，不伪造可用 |
| 3 | reauthenticate | Runtime | 过期/失效账号恢复或明确失败分类 |
| 4 | model_discovery | Runtime+API | 非空目录，`(provider,upstream)` upsert |
| 5 | chat 文本非流式 | Runtime+API | HTTP 200，有 choice/content |
| 6 | chat 文本 SSE | Runtime+API | HTTP 200，多数据帧 + `[DONE]` |
| 7 | chat 图片非流式 | Runtime+API | 声明 vision 的模型返回描述 |
| 8 | chat 图片 SSE | Runtime+API | 同上，流式闭环 |
| 9 | chat 文件非流式/SSE | Runtime+API | 仅 LongCat/Arena，内容可被模型读取 |
| 10 | AUTO 回退 | AUTO | API 分类失败后 Runtime 成功 |
| 11 | 显式 API 失败关闭 | API | 不静默改走 Runtime |
| 12 | daily_checkin | Runtime | 仅 MiniMax，自然调度真实结果 |

## 生产真实验收矩阵（2026-09-14，namespace=any2api）

通道约定：AUTO KEY 优先 API；另建 API-only / RUNTIME-only KEY 对照。smoke 不携带 `max_tokens`。

| Provider | 文本collect | 文本SSE | 图片collect | 图片SSE | keepalive | 主要阻断 |
|---|---|---|---|---|---|---|
| deepseek | PASS | PASS | N/A text-only | N/A | PASS 1864 | 首字节延迟偏高 |
| mimo | PASS | PASS | PASS* | PASS | PASS 2812 | *32x32 返回拒答文案但 HTTP 200 |
| longcat | PASS | PASS | FAIL empty | FAIL empty | PASS 2884 | 双通道图片均 empty_model_response |
| glm | PASS | PASS | FAIL | FAIL | PASS 2687 | API 图片 400 Content Security；Runtime 流失败；部分 anti_bot |
| arena | PASS (Runtime) | 待补 | 待补 | 待补 | PASS 59 | API 401 User not found；reauth interactive_auth_required |
| qwen | PASS | 部分 | 待补 | 待补 | PASS 55927 | 间歇 502 RuntimeError；reauth QwenReauthenticationRequired |
| minmax | FAIL | FAIL | 待补 | 待补 | 历史 PASS | 探针/推理 model_unavailable；user/info 200 后动作仍 502 |

## 现状基线（2026-09-13 盘点）

### 生产账号（any2api）

| Provider | ACTIVE/enabled | 其他 |
|---|---:|---|
| arena | 7 | 2 EXPIRED |
| deepseek | 16 | — |
| glm | 23 | — |
| longcat | 26 | — |
| mimo | 57 | — |
| minmax | 7 | 29 PENDING, 1 DISABLED |
| qwen | 12 | 34 PENDING, 1 EXPIRED |

### E2E 账号（any2api-e2e-20260817）

| Provider | 状态 |
|---|---|
| deepseek | ACTIVE 1 |
| glm | DEGRADED 1 |
| longcat | ACTIVE 1 |
| mimo | ACTIVE 1 |
| minmax | PENDING 1（无可用） |
| qwen | PENDING 1（无可用） |
| arena | 无账号行 |

### 通道配置

- E2E：`deepseek/glm/longcat/mimo/qwen` = `API`；`arena/minmax` = 默认
- 生产：7 家均未写 `inference_transport_mode`（走 AUTO 默认）

### 2026-09-14 生产真实验收矩阵（实测）

图例：`PASS` 真实成功；`FAIL` 有明确错误；`N/A` 未声明能力。

| Provider | keepalive | 文本 collect | 文本 SSE | 图片 collect | 图片 SSE | 主用通道 | 备注 |
|---|---|---|---|---|---|---|---|
| DeepSeek | PASS（历史 1864） | PASS | PASS | N/A | N/A | API | text-only；首字 50–120s |
| MiMo | PASS（2812） | PASS | PASS | PASS | PASS | API | 纯色极小图可能拒答 |
| LongCat | PASS（2884） | PASS | PASS | PASS（≥32px 棋盘/256px） | PASS | API | 极小纯色 PNG 可 `empty_model_response` |
| GLM | PASS（2687） | PASS | PASS | PASS（`glm-4.6v`） | PASS | API | 文本/图片均 40–70s |
| Qwen | PASS（55927） | PASS（Runtime） | PASS（Runtime，含 pong） | 上传 PASS / 完成 FAIL | — | Runtime | 图片 STS 改签名 HTTP 已通；completion `captcha_rejected`/空帧 |
| MiniMax | PASS（签到后） | PASS | PASS | FAIL（400 ownership） | FAIL | Runtime+签名 fetch | 文本双通道 READY；图片 attachment `invalid_params` |
| Arena | PASS | PASS | PASS | PASS | 待补 | Runtime | 新号已激活；`claude-sonnet-4-6` 图片返回 Blue |

### 本轮已落地修复

1. **MiniMax request profile**：GitOps `b7cd0b2` 将 `ANY2API_AUTOMATION_MINMAX_{SIGNATURE_SALT,YY_SALT,VERSION_CODE}` 注入 Automation；重启后 `_official_profile()` 可解析。
2. **MiniMax arrayBuffer 桥接**：分支提交 `1fe3b8b` 已兼容非 `Response` 对象；**生产镜像仍为 `d68dc20`，待 CI 发布**。
3. **LongCat event_error**：兼容 `eventError` 字段名（工作树）。
4. **参数契约**：多家 Provider 不接受 `max_tokens`，smoke 必须按厂商契约发参。

### 当前阻断点

1. **MiniMax**：需部署含 `1fe3b8b` 的 Automation 镜像（`arrayBuffer` 修复）。
2. **Arena 账号池枯竭**：9/9 EXPIRED；`arena_interactive_auth_required` × 222，无法自动 reauth。
3. **Qwen 流式**：Runtime SSE `NS_BINDING_ABORTED`；非流式 Runtime 可用。
4. **模型可用性门禁**：探针新鲜度窗口内无成功 usage 时返回 `model_unavailable`。

## 实施阶段

### Phase 0 — 现状盘点与缺口矩阵

- [x] 读生产/E2E 账号、模型、provider 配置
- [x] 写出逐厂商 × 逐用例矩阵
- [x] 确认内部 token / admin / public key 可用
- [x] 建立可重复 smoke 脚本入口（`scripts/fullchain_smoke.py`、`scripts/admin_login_probe.py`）

### Phase 1 — 公共层与代码级缺口

- 核对 7 家 API/Runtime binding 覆盖声明的 inference_actions
- 核对 LongCat `model_discovery` 是否缺失（当前仅 `chat`）
- 核对多模态在 API channel 的上传路径
- 补齐失败分类、channel 日志、契约测试

### Phase 2 — 逐厂商真实验收

顺序：DeepSeek → MiMo → LongCat → GLM → Arena → MiniMax → Qwen

每家完成：keepalive → discovery → 文本双通道 → 多模态双通道 → AUTO 回退证据。

### Phase 3 — 缺账号厂商注册补齐

对 E2E 缺账号的 Arena/MiniMax/Qwen 走 Runtime 注册；生产只补必要账号，不批量刷号。

### Phase 4 — 发布与生产同步

- GitHub Actions 质量检查
- E2E 全绿后同步生产
- 版本按 SemVer 递增

## 验收标准

1. 七家均有：Runtime keepalive + 文本非流式/SSE 真实成功
2. 声明图片的厂商：Runtime 与 API 图片非流式/SSE 真实成功（或明确厂商阻断证据）
3. LongCat/Arena 文件输入：内容读取真实成功或明确未就绪
4. AUTO 回退与显式 API 失败关闭均有日志/请求证据
5. MiniMax daily_checkin 有自然调度真实结果
6. CI 三端 quality + 镜像构建通过
7. 生产 Pod Ready、零重启、无新增 OOM

## 验证约束

- 本机不执行完整编译构建；代码质量由 GitHub Actions 执行
- 本机可执行：静态检查、git diff、K8S 查询、集群内 smoke
- 真实验收优先 E2E namespace，生产变更需独立确认
