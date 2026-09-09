# 厂商闭环、Action Channel 与 Runtime 内存治理

## 目标

在统一 Action/Runtime/API Channel 边界下，逐厂商完成真实 Web/CLI 反代验收，并消除 Automation Pod 因多棵浏览器进程树并存导致的 OOMKilled。Runtime 仍是默认渠道，API 只按 Action binding 逐项验证和启用。

## 范围

- 由进程级浏览器预算统一约束官方页面 Runtime、Qwen 风控浏览器、GLM 临时浏览器、注册/重新认证/保活浏览器，以及 Grok CLI 辅助浏览器。
- 每个浏览器实例从启动前获取 lease，在 context、browser 和 Playwright 驱动全部关闭后释放；异常和取消路径也必须释放。
- 真实验收按厂商逐个执行文本、Responses 协议、图片输入和文件上传边界；音频、视频只有在当前上游 Web/CLI 页面和适配器均有真实证据时才标记为支持。
- 厂商状态必须区分 `ready`、账号/额度阻断、上游协议变化、Runtime 风控阻断和未配置账号，不能用一次失败推导永久结论。

## 非目标

- 不接入厂商公开渠道 API；API Channel 只承载已审核的 Web/CLI API 反代实现，不能绕过统一 Action、账号隔离和额度/风控状态。
- 不在没有有效账号、额度或真实上游响应的情况下启用 Grok 或宣称多模态就绪。
- 不删除现有分发密钥、账号或历史文档；旧报告只在有新证据后归档或补充当前结论。

## 验收标准

- `automation` 单元测试、ruff、Backend 构建和 WEB 构建通过。
- 生产 Automation Pod 在滚动发布后保持 Ready，浏览器进程树总数受 `ANY2API_AUTOMATION_BROWSER_PROCESS_CAPACITY` 约束，连续观察期间无新的 OOMKilled。
- 已有 MinMax 的打卡、账号探测、文本 completion 和图片路径保持通过。
- Qwen、MiMo、DeepSeek、GLM、LongCat 逐厂商记录真实请求结果；没有真实证据的能力维持未就绪，不伪造通过状态。
- Grok 三个渠道仍以实际账号和上游响应为准，不因预算修复自动启用。

## 当前实现决策

生产默认浏览器进程预算为 3。原因是单棵 Camoufox 进程树在当前 K8S 节点约占数百 MiB 到 1 GiB，原 Automation Pod 在 6 GiB limit 下同时存在 6 棵 Camoufox 树并伴随 Chromium/驱动进程，已出现 exit code 137；预算优先保证服务稳定，再按实测逐步调高。
