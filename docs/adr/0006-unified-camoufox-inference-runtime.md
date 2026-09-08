# ADR-0006：统一 Camoufox Browser Runtime 作为上游出站边界

- 状态：Accepted
- 日期：2026-09-08
- 取代：`ADR-0003` 关于 Java 持有厂商物理路径、header、签名和请求体的部分约定；`ADR-0005` 关于仅部分 provider 使用 Official Browser Runtime 的范围约定

## 背景

项目同时存在 Java direct WebClient、Python curl-cffi browser-shaped HTTP、页面 WebSocket 和 Camoufox 官方前端 Runtime。它们最终都依赖上游厂商的接口、密钥、参数和风控变化，导致维护边界分散：有些变化要改 Java，有些要改 Python，有些还要同时修改保活和推理路径。

用户侧真正稳定的契约是 OpenAI-compatible 语义请求和统一事件结果，不是厂商的 URL、header 或签名。将物理出站边界统一到 Camoufox，可以让厂商的页面协议变化集中在 Python provider adapter/runtime 内处理，并利用真实浏览器上下文承载 cookie、localStorage、IndexedDB、指纹、挑战和官方前端代码。

## 决策

所有上游厂商的文本推理、模型发现和账号保活，统一通过 Python Camoufox Browser Runtime 出站。Java 不直接调用这些推理上游域名。注册、密码登录、OAuth/token 交换等身份恢复步骤仍属于 provider 生命周期 adapter，可以保留其自身的认证协议或页面流程，但不能回流为 Java 推理旁路。

“统一 Runtime”是顶层边界统一，不是强制所有厂商共享同一物理实现。Runtime 内允许以下按厂商选择的策略：

1. 调用官方前端 bundle 暴露的函数；
2. 在页面主世界执行页面 `fetch`；
3. 在页面主世界创建 WebSocket；
4. 只有前三种无法满足时，才使用 UI 操作完成必要步骤。

页面内直接执行请求不等于模拟点击；点击只用于确实由页面交互触发、且无法通过受控页面 API 完成的动作。

Java 保留业务编排职责：CanonicalRequest 校验、provider/model 路由、账号 lease、账号状态、凭据版本合并、统一事件和用量审计。Python 保留物理访问职责：浏览器/context 生命周期、账号状态恢复、代理亲和、页面请求、原始帧和 Runtime 诊断。

## 取舍

优点：

- 厂商 URL、动态 header、前端生成的签名和风控上下文集中在 Python；
- 账号保活、切换和推理共享同一隔离 context，降低串号和状态漂移；
- Java provider 适配器更接近语义映射，不再承担厂商协议细节；
- 仍然可以利用官方前端实现，减少手写逆向协议的范围。

代价：

- 浏览器进程和 context 的资源成本高于 direct HTTP；
- 页面启动、bundle discovery、前端重构会成为新的可观测维护点；
- Camoufox 版本、指纹和运行时配置必须作为不可变 revision 管理；
- 没有真实账号 completion/保活/切换验收前，不能删除旧实现。

## 约束

- Camoufox 是默认且可观测的后端；Patchright 只能作为显式兼容兜底，不能静默改变协议行为。
- Java -> Python 只传递 semantic command、受控 runtime plan 和账号执行上下文；禁止下发任意 JavaScript、任意上游 URL 或未审核的 header。
- Runtime 返回的 credential patch 必须经过 Java 的 credential version guard；Python 不直接写业务账号状态。
- 所有迁移以 provider 为单位分阶段完成；旧推理路径已删除，生命周期/media 兼容代码仍保留并与推理 Runtime 隔离。每个 provider 的运行时规则可以通过 candidate/canary 回滚。

## 验证

每个 provider 至少验证：成功 completion、流式/非流式结果、账号保活、账号切换隔离、认证失效恢复、代理/网络失败分类和 credential patch 版本保护。本次已完成本地静态边界、Python 单元测试、Java 全量测试；静态测试和构建通过不能替代真实上游验收。
