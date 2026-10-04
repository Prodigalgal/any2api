# Redis 缓存等待与协调错误收敛（0.26.3）

## 目标、范围与基线

基线 0.26.2，源码 3c92523、文档 7f974ce，Argo Synced/Healthy。2026-10-03 的真实 Codex 补查存在两次生成前 QueryTimeoutException，同窗口 Redis L2 读写及 RedisCommandTimeoutException。2026-10-04 只读 Lettuce 计时累计平均约 136ms、近期 max 168ms，Hikari pending=0；这些时点数据不证明历史超时的全部根因。

将 API Key、模型目录、prompt 的非关键缓存 Redis 等待限制为默认 250ms，失败沿已有数据库/空缓存回源规则；保留 single-flight、TTL、权限、失效和异步写入。关键账号租约获取/续租的超时/连接失败按统一 OpenAI 错误返回可重试 coordination_unavailable：提交 SSE 前 HTTP 503，SSE 已提交后标准 failed/error 终态。严格保持租约失败不得授予容量，续租错误不得误入厂商错误处置或触发模型熔断。

AccountLeaseService 的释放失败仍传播明确异常；生成收尾捕获该协调异常、记录请求/账号上下文，依赖已有租约 TTL 清理，不强制删除/伪造释放成功。此时已提交的生成终态保留，防止先 completed 后 failed 的冲突终态；原始生成失败/续租失败仍保留。

## 非目标与影响文件

不迁移 Redis/PV，不改变全局 3s 协调超时、账号容量、Key、数据库结构、厂商生成和默认存储规则；不承诺网络根因已解决或所有 Read 分位数变快。Qwen 仍排除。涉及 LayeredJsonCache、三处缓存调用方、Any2ApiProperties/application.yml、AccountLeaseService、InferenceCoordinator/ModelRuntimeGuard/InferenceTelemetryService、ApiExceptionHandler/OpenAiResponseWriter、相关测试、版本文件和验证记录。

## 验收、验证与回滚

- Redis 慢/永不返回时有界回源，快缓存保持命中；并发回源一次，空结果和数据库错误正确传播，写入/失效等待有界，日志无秘密。
- lease acquire/exclusive/renew/release 的超时/连接失败不伪造成功；容量拒绝、正常成功和其他编程错误语义保留。
- 两协议覆盖 JSON 503、首帧前 503 和已提交 SSE 的错误终态，错误不泄露 Redis endpoint/内部异常信息。
- 相关单测、全量 Backend/bootJar、Automation/Web/版本契约、官方 SDK fixture；既有授权下提交、CI/GitOps/四组件运行验证，再进行有限真实厂商/Read 复测。失败样本独立保留。
- 回滚到 0.26.2 四个不可变镜像（源码 3c92523），无需数据库/Key 恢复。缓存配置恢复默认原逻辑；新的协调错误归一化撤回。
