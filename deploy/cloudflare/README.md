# OpenAI 公网转发候选

## 目的和当前状态

真实验收中，公网入口出现 Cloudflare 自身的 502/524，正文不符合 OpenAI error 契约；直连入口能够返回网关的 JSON。此 Worker 候选将公开 OpenAI 路径转发至现有 HTTPS 直连地址，原样返回 status、headers、body stream，不缓存、不记录请求正文或凭据。非 OpenAI 路径走原入口；域名和路径固定，不能由调用方指定任意上游。

当前只完成 Node 测试和 Wrangler dry-run，**未发布、未改外部路由**。Workers 的 HTTP 执行时间与普通 CDN 代理不同，但实际 origin fetch、长请求和断连行为仍须发布后验证，不能据此提前宣布 524 已解决。

## 发布前置条件

- 用户明确授权既有 Cloudflare 账号的 Worker 与两条 route 写入；按项目 AGENTS.md 的外部 SaaS 写入停止条件处理。
- `wrangler login` 后 `wrangler whoami` 确认既有账号及目标 zone 可访问。不把 token 写入仓库、日志或报告，不新建账号。
- 核对现有两条 route 是否已有 Worker 绑定，保存原 route 绑定和目标 Worker 当前 deployment/version ID；若与已有功能冲突，停止覆盖。
- 验证 `any2api-direct.mnnu.eu.org` 的 HTTPS、OpenAI 鉴权和 SSE 可用。

## 本地验证与发布

在仓库根目录运行：

```powershell
node --test deploy/cloudflare/openai-gateway.test.mjs
wrangler deploy --dry-run --config deploy/cloudflare/wrangler.jsonc
wrangler whoami
wrangler deploy --config deploy/cloudflare/wrangler.jsonc
wrangler deployments list --config deploy/cloudflare/wrangler.jsonc
```

记录 source commit、Worker version/deployment ID、UTC 时间和实际 route。Worker version 由 Cloudflare 生成，不覆盖旧制品；应用版本与此发布记录关联。

## 生产验收

使用受限、短期 Key，结束后删除：

1. public/direct 的 400、401、403、404、503 比对 OpenAI error code、request_id、Content-Type 和 status。
2. 实际厂家生成失败产生的 502 与网关 JSON 比对；504 如未真实触发，明确区分本地受控通过与线上未触发。
3. 常用 Chat/Responses 与官方 SDK 流累积通过；超过 CDN 原有等待窗口的真实请求验证长请求；SSE 不能等全部生成结束才发送。
4. 客户端分别在首输出前、首输出后断连，核验网关取消遥测和账号租约释放。
5. 管理页面及 `/api/admin` 仍走原链路，路由和权限不变。

## 回滚

已有 Worker 可执行 `wrangler rollback <已记录的 version ID> --config deploy/cloudflare/wrangler.jsonc --message <回滚原因>`。首次启用时恢复发布前保存的两条 route 绑定，或移除本次新增绑定；不要删除未被本次创建的 route/Worker。应用镜像独立按 GitOps 回滚，此 Worker 不改数据库、DNS 或 PV。

## 官方依据

- [Workers HTTP duration](https://developers.cloudflare.com/workers/platform/limits/)
- [Cloudflare error 524](https://developers.cloudflare.com/support/troubleshooting/http-status-codes/cloudflare-5xx-errors/error-524/)
- [Wrangler deploy/rollback](https://developers.cloudflare.com/workers/wrangler/commands/workers/)
- [Cloudflare structured error responses](https://developers.cloudflare.com/fundamentals/reference/error-responses/)
