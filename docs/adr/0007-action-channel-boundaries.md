# ADR-0007：统一 Action 契约并拆分 Runtime / API Channel

- 状态：Accepted
- 日期：2026-09-09
- 取代：`ADR-0006` 中“推理只能使用 Camoufox Runtime”的选择策略；`ADR-0006` 关于 Runtime 作为安全默认边界的部分继续有效

## 背景

系统的稳定契约是注册、重新认证、保活、签到、模型发现、对话和媒体处理等业务动作，
不是厂商的 URL、前端 bundle、签名、cookie 或 HTTP 参数。过去 Provider 方法直接携带
`runtime_mode`，同一个方法内部同时判断 API 和 Runtime，导致业务语义、通道选择和厂商
协议逐渐耦合。

项目同时需要两种物理出站方式：

- Runtime：在账号隔离的 Camoufox/兼容浏览器上下文中执行官方 Web/CLI 请求链路；
- API：使用已验证的 Web API/CLI API 请求链路和账号凭据，不代表厂商公开渠道 API。

这两种方式都可能随着上游页面脚本、密钥、接口或参数变化而变化，但变化应被限制在
对应 Provider 的 Channel 实现中，不能扩散到账号租约、模型路由和 OpenAI 兼容协议层。

## 决策

### 1. Action 是唯一的业务入口

系统统一使用以下 Action 名称：

| Action | 语义 |
|---|---|
| `register` | 注册账号并建立初始凭据 |
| `reauthenticate` | 重新认证并刷新账号上下文 |
| `keepalive` | 验证账号登录态和可用性 |
| `daily_checkin` | 执行厂商每日签到并记录结果 |
| `model_discovery` | 获取厂商当前可用模型 |
| `chat` | 执行统一对话请求并输出事件流 |
| `provider_query` | 获取 Provider 内部业务资源，例如 agent 列表 |
| `media_policy` / `media_callback` | 执行厂商媒体上传前后的受控步骤 |
| `raw_request` | 仅用于旧 transport 兼容和已审核的受控路径 |

Action 请求只包含语义命令、账号执行上下文、代理亲和、运行时规则和请求关联信息；
不允许业务层拼接任意上游 URL、任意 JavaScript 或未审核 header。

### 2. Channel 是唯一的物理出站实现

Action Dispatcher 根据已解析的渠道策略选择一个具体 Channel：

```text
OpenAI-compatible request / lifecycle command
                  |
                  v
        Java semantic Action + account lease
                  |
                  v
          Action Dispatcher / policy
             |                 |
             v                 v
    RuntimeChannel          ApiChannel
  browser/context/bridge   signed Web API/CLI HTTP
             |                 |
             +--------+--------+
                      v
             provider-specific action binding
                      |
                      v
                upstream Web/CLI
```

RuntimeChannel 和 ApiChannel 不共享账号 cookie、storage state、fingerprint、签名状态或
代理会话；它们只共享 Action 契约、账号快照、租约边界、错误分类和观测字段。相同 Provider
的多账号可以复用进程预算和 HTTP 客户端能力，但必须按账号隔离执行上下文。

### 3. Provider 只注册 Action binding

Provider 负责三件事：

1. 声明支持的 Action；
2. 为每个 Action 注册 Runtime 或 API binding；
3. 在 binding 内实现该厂商的请求构造、签名、页面桥接、媒体步骤和事件解码。

Provider 不负责选择渠道，也不在同一业务方法中分叉 `if API / if Runtime`。渠道选择、
能力缺失和兼容错误由 Dispatcher/Channel 统一处理。

当前兼容迁移允许旧 Provider 的 `transport_request/transport_stream` 自动注册为 Runtime
binding。这样旧行为保持不变；新 API 实现必须以独立 API binding 接入。MinMax 是首个
已拆出 API binding 的 Provider，当前 API binding 覆盖文本/模型及受控查询动作，媒体上传
仍要求 Runtime；其他 Provider 当前保持 Runtime binding，未经真实验证不得显示为 API 就绪。

### 4. AUTO 只属于选择策略

`AUTO` 不是 Channel，也不会下发到 Automation。它由 Java 策略层解析为具体的 primary
和可选 fallback：

- 对已具备 API binding 的 Action，优先 API；
- API 在首个业务输出前发生可恢复的传输/上游失败时，才回退 Runtime；
- Action 没有 API binding 时直接选择 Runtime；
- 认证拒绝、参数错误、媒体能力不支持和业务额度错误不做盲目回退。

### 5. 生命周期和推理共用 Action 边界，但不共用物理状态

注册、重新认证、保活、签到、模型发现和对话都通过同一 Action/Channel 调度边界进入
Automation。生命周期仍默认使用 Runtime；未来只有在某个 Provider 的 API binding 经过
真实账号、代理和账号切换验证后，才可以单独开放 API 或 AUTO。

账号状态、额度、租约、凭据版本合并和最终可用性仍由 Java 维护。Python 只返回结果、
脱敏诊断和 credential patch，不直接改变业务账号状态。

## 取舍

优点：

- 业务动作与厂商物理协议解耦；
- API 与 Runtime 可以按 Action 粒度逐项迁移，不需要一次性重写所有 Provider；
- Runtime 仍然是默认安全路径，API 验证失败不会改变既有生产行为；
- 同一 Action 的错误、超时、账号隔离和观测字段可复用。

代价：

- 需要维护 Action binding 矩阵，不能再用“Provider 支持 API”推导每一种媒体能力；
- 同一 Action 可能需要两套厂商协议实现和两套测试 fixture；
- API 签名、密钥和参数变化仍需要更新对应 API binding，Runtime 页面变化仍需要更新
  对应 Runtime binding；抽象减少影响面，但不会消除上游变化。

## 迁移与验收

迁移顺序固定为：

1. 先接入 Action 名称、请求契约、Channel Dispatcher 和旧 transport 兼容转换；
2. 将现有生命周期和 Runtime 推理注册为 Runtime binding；
3. 以 Provider 为单位新增独立 API binding，不在 Provider 主类中加入模式分支；
4. 按 `provider + action + model + channel + media kind + account` 做真实 K8S 验证；
5. 验证成功、失败、认证失效、账号切换、代理亲和、流式取消、credential patch 和 OOM
   边界后，才允许开启 API/AUTO 或删除旧兼容路径。

静态测试和本地 fixture 只能证明契约和边界行为，不能替代真实上游 completion、保活、
签到、媒体上传和账号切换验证。
