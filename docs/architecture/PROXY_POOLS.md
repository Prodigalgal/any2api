# Proxy Pools

## Data model

`proxy_pools` stores pool metadata and an AES-256-GCM encrypted source. `provider_proxy_bindings` gives each provider at most one pool; a pool can be shared by several providers. The encrypted source has a per-revision nonce and AAD, so ciphertext cannot be copied between pools or revisions.

For Compose deployments, pass `ANY2API_PROXY_BOOTSTRAP_DIRECTORY` only when that container path is mounted from a secret source. Do not put proxy nodes into `.env` or the Compose file. Kubernetes mounts the Oracle node Secret read-only and imports it into the named pool after Liquibase has created the schema.

Supported pool modes:

- `SUBSCRIPTION_URL`: one HTTPS endpoint returning proxy nodes.
- `NODE_LIST`: 1 to 500 newline-separated VLESS, HTTP, HTTPS, SOCKS5, or SOCKS5H nodes.

Read APIs expose only `sourceConfigured`, mode, node count, status, timestamps, and provider IDs. Operators replace a source by submitting a new value; leaving it empty during an edit preserves the existing ciphertext.

## Flow semantics

Java resolves the provider binding when a registration or lifecycle action starts and sends the decrypted runtime pool only over the authenticated internal automation API. Python reserves one node with Redis `SET NX`, starts sing-box when required, and holds that node until the flow finishes. A retry is a new flow and may choose another node.

Only vendor browser and HTTP traffic uses the leased proxy. PostgreSQL, Redis, Java callbacks, subscription downloads, temporary mail, and local captcha solvers bypass it. Follow-up calls within one flow share the same egress, including Qwen Baxia generation plus sign-in and Grok login plus OAuth token exchange.

VLESS supports WebSocket/TLS and TCP REALITY with XTLS Vision. Direct HTTP and SOCKS nodes do not start sing-box but still use the same Redis node-exclusivity lease.

## Bootstrap

`ANY2API_PROXY_BOOTSTRAP_DIRECTORY` may point to a read-only Kubernetes Secret volume containing one or more node files. At application readiness, Java imports all non-comment lines into `ANY2API_PROXY_BOOTSTRAP_POOL_NAME`, encrypts them in PostgreSQL, and preserves existing provider bindings. Logs contain only the pool name and node count.

Bootstrap files and node URIs must stay outside the public repository. The GitOps deployment references only an existing Secret name.
