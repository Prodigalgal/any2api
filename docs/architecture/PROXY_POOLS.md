# Proxy Pools

## Data model

`proxy_pools` stores pool metadata and an AES-256-GCM encrypted source. `provider_proxy_bindings` gives each provider at most one pool and records its `INFERENCE`, `LIFECYCLE`, and/or `REGISTRATION` traffic scopes; a pool can be shared by several providers. The encrypted source has a per-revision nonce and AAD, so ciphertext cannot be copied between pools or revisions.

For Compose deployments, pass `ANY2API_PROXY_BOOTSTRAP_DIRECTORY` only when that container path is mounted from a secret source. Do not put proxy nodes into `.env` or the Compose file. Kubernetes mounts the Oracle node Secret read-only and imports it into the named pool after Liquibase has created the schema.

Supported pool modes:

- `SUBSCRIPTION_URL`: one HTTPS endpoint returning proxy nodes.
- `NODE_LIST`: 1 to 500 newline-separated VLESS, HTTP, HTTPS, SOCKS5, or SOCKS5H nodes.

Read APIs expose only `sourceConfigured`, mode, node count, status, timestamps, provider IDs and binding scopes. Operators replace a source by submitting a new value; leaving it empty preserves the source and re-encrypts it for the new revision. Binding edits replace the target pool's bindings, so read current metadata first and preserve unrelated providers/scopes.

## Flow semantics

Java resolves the provider binding and traffic scope when inference, registration or lifecycle work starts and sends the decrypted runtime pool only over the authenticated internal automation API. Python reserves node concurrency with Redis (`SET NX` for exclusivity, a token set for shared capacity) until the flow finishes. VLESS tunnels are pooled by full node configuration; requests and browser sessions hold separate references. Releasing a request does not destroy a tunnel still used by a browser. Idle, unreferenced tunnels expire or can be evicted under capacity pressure.

Account affinity orders nodes by stable node identity. Arena uses strict affinity for an existing session; a busy or unavailable selected node does not silently move that session to another egress. When an operator changes the pool, recheck the affinity against the new pool and allow the browser runtime to replace a session whose actual proxy URL changed. Direct HTTP URLs and VLESS identities differ; do not calculate VLESS affinity by hashing the full subscription URI.

Only vendor browser and HTTP traffic uses the leased proxy. PostgreSQL, Redis, Java callbacks, subscription downloads, temporary mail, and local captcha solvers bypass it. Follow-up calls within one flow share the same egress, including Qwen Baxia generation plus sign-in and Grok login plus OAuth token exchange.

VLESS supports WebSocket/TLS and TCP REALITY with XTLS Vision. Direct HTTP and SOCKS nodes do not start sing-box but still reserve node concurrency.

## Bootstrap

`ANY2API_PROXY_BOOTSTRAP_DIRECTORY` may point to a read-only Kubernetes Secret volume containing one or more node files. At application readiness, Java imports all non-comment lines into `ANY2API_PROXY_BOOTSTRAP_POOL_NAME`, encrypts them in PostgreSQL, and preserves existing provider bindings. Logs contain only the pool name and node count.

Bootstrap files and node URIs must stay outside the public repository. The GitOps deployment references only an existing Secret name.

## 当前生产绑定（2026-10-18 23:57，UTC+8）

- 六家启用厂商 Arena、Grok、MiMo、MiniMax、LongCat、GLM 均绑定 `Self-hosted Oracle`，三个流量作用域齐全。DeepSeek/Qwen 保留历史绑定但提供商停用、部署零副本。
- Self Proxy 四节点入口为 `168.138.40.52`、`168.138.40.214`、`213.35.105.106`、`217.142.229.2`，节点 URI/认证材料只在加密源和受限服务器管理目录。
- 四节点生产租约路径均取得 gstatic 204 和有效出口。当前出口实测均为 Oracle 原生 IPv6；退回 Self Proxy 指退回原 VLESS 池，并不强制 IPv4。
- 独立 HE 固定 IPv6 候选池 `Arena IPv6 - Oracle 40.52` 已解除 Arena 绑定并停用，保留配置及服务用于回滚。其节点 1/2 有完整生成，其他节点/账号仍被官方 v2 拦截，未通过稳定验收。
- 优先恢复的 Arena 会话使用 Patchright，固定至 Sophia `168.138.40.214`，与本机 HY2 成功链路出口一致；切回后一次公网 Responses 仍被官方 v2/限流拦截。代理连通、页面 HTTP 403 和应用健康均不能代替有效生成。

运行证据及回滚见 [1.5.8 发布记录](../reports/RELEASE_1.5.8_ARENA_PAGE_WORLD_2026-10-18.md)。

## 历史代理延迟样本（2026-10-16）

以下延迟摘自 [历史评测报告](../reports/PROXY_POOLS_COMPATIBILITY_BENCHMARK_2026-10-16.md)，仅保留当时采样记录。不能据其推定当前账号、验证码、工具、多轮或长流式生成已通过验收，也不作为停用厂商的启用建议。

| Provider | 历史比较 | 历史延迟样本 |
| :--- | :--- | :--- |
| **DeepSeek** | Oracle / CF | 181 / 205 ms；目前已停用 |
| **Grok** | Oracle / CF | 579 / 1151 ms |
| **Arena** | Oracle / CF | 420 / 610 ms；不保证浏览器或官方验证通过 |
| **MiMo** | Oracle / CF | 409 / 513 ms |
| **MiniMax** | Oracle / CF | 565 / 739 ms |
| **LongCat** | Oracle / CF | 1347 / 2594 ms |
| **ChatGLM** | Oracle / CF | 875 / 441 ms |
| **Qwen** | Oracle / CF | 546 / 344 ms；目前已停用 |

