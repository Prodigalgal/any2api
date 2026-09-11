# Runtime 生命周期过期凭据修复验收（2026-09-11）

## 结论

`0.14.1` 已完成 K8S 部署，三套业务 Deployment 均切换到同一不可变镜像，Pod Ready、重启次数为 0，未发现新的 `OOMKilling` 事件。

本次修复已在 Qwen 运行态生效：过期凭据的 `keepalive` 不再被当作正常生命周期完成，而是记录 `credential_refresh_scheduled`，账号被置为 `EXPIRED/disabled`，并生成 `reauthenticate` 调度动作。成功重新认证且厂商没有返回新的过期时间时，会清理旧的过期 fence，避免账号因历史 `expires_at` 永久停留在过期状态。

这次验收尚不能把 Qwen 标记为 Ready：第一批重新认证已经捕获到真实的 `provider_transport_error`，Automation 的 Qwen SSE 响应在推理探针阶段因 `PrematureCloseException` 提前关闭；Qwen 仍需继续观察重试成功率和上游稳定性。

## 发布链路

| 项目 | 结果 |
|---|---|
| 业务版本 | `0.14.1` |
| Source commit | `fc33232`（包含生命周期修复及三端版本统一） |
| 生命周期修复 commit | `de441c0` |
| CI | `34556906088`，质量检查、三套镜像构建、GitOps 更新均成功 |
| GitOps commit | `46b6e98`，本地 checkout 与 `origin/main` 已同步 |
| Argo CD | `Synced / Healthy / Succeeded`，revision `46b6e985...` |
| K8S 镜像 | server、automation、web 均为 `*-sha-fc332321da7b...` |
| K8S 运行态 | 三套业务 Pod Ready，重启次数均为 0 |
| OOM | `reason=OOMKilling` 无事件；Automation 当前约 `1361Mi`，内存 limit `6Gi` |

## 代码行为

1. 生命周期结果先计算凭据的有效期；已过期的 `keepalive` 或其他生命周期动作会进入 `credential_expired` 分支。
2. 过期账号被置为 `EXPIRED` 且禁用，不再继续占用可用账号池；调度器生成重新认证动作，并保留明确错误分类。
3. 重新认证成功但上游没有返回新的有效期时，仅在旧过期时间已经失效的情况下清理旧 `expires_at` fence 和凭据过期 fence；不会覆盖厂商返回的新过期时间。
4. 重新认证失败向上保留 `provider_transport_error` 等真实错误，不会因为浏览器返回 HTTP 200 就伪造账号可用。

## Qwen 真实运行证据

部署后日志出现多次：

```text
provider=qwen operation=keepalive status=SUCCEEDED stage=credential_refresh_scheduled
```

数据库快照显示：

| 状态 | enabled | 数量 | 过期 fence 仍有效/未知 | 已过期 |
|---|---:|---:|---:|---:|
| ACTIVE | true | 6 | 3 | 3 |
| EXPIRED | false | 40 | 0 | 40 |
| PENDING | false | 1 | 1 | 0 |

重新认证任务开始到期执行后，第一笔结果为：

```text
operation=reauthenticate
status=FAILED
stage=inference_probe
error_code=provider_transport_error
error=PrematureCloseException
```

对应 Automation 日志为 Qwen `/api/v2/chat/completions` 请求 `NS_BINDING_ABORTED`，服务端记录为推理探针失败，而不是认证成功。该证据同时说明账号生命周期分类和失败回写已经打通，但 Qwen 上游/浏览器响应仍不稳定。

## MiniMax 打卡状态

MiniMax 当前保留 7 个 `daily_checkin` 待执行动作，最早到期时间为 `2026-09-11 07:02 UTC`（中国时间 15:02）。本次不提前调用、不伪造打卡完成事件；应以自然调度后的真实 `SUCCEEDED` 或明确失败结果作为验收依据。

## 后续验收边界

- Qwen 继续观察 reauthenticate 的重试成功率、账号恢复数量及新一轮 completion；在 transport error 未收敛前保持 `DEGRADED`。
- Qwen 图片 1x1 的上游 `invalid_input` 仍是明确的不可重试输入错误；32x32 图片非流式/SSE 成功不能替代尺寸边界验收。:codex-annotation{index="1"}
- MiniMax 等待自然 `daily_checkin`，不绕过厂商额度和打卡规则。
- API Channel 仍后置，本报告仅覆盖 Runtime 和生命周期动作。
