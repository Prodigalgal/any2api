# 分发 API Key 清理与按厂商重建

## 目标与授权

用户明确要求清理所有分发 API Key，创建成一个厂商一个 Key，并保存到服务器管理文件夹。执行前生产有 33 个分发 Key、8 个已登记厂商，源码/部署版本为 0.25.7。

## 范围与实施

- 使用现有 Admin API 创建、删除及失效认证缓存，不绕过应用直接改库。
- 八个新 Key 分别只允许 arena、deepseek、glm、grok_web、longcat、mimo、minmax、qwen 中的一个厂商及该厂商全部模型。协议为 CHAT_COMPLETIONS / RESPONSES，features 为 TOOL_CALLING / MULTIMODAL_INPUT / FILE_UPLOADS，transportMode=AUTO，无过期时间。
- 先创建新 Key 并写入受限本地文件、核验权限，再删除执行前的全部旧 Key；避免未保存一次性明文就撤销旧凭据。出现异常保存已完成状态，禁止重复创建导致多 Key。
- 目标目录为 `D:\WorkSpace\Project\服务器管理\private\any2api\distribution`，保存 ENV、JSON、使用说明及不含明文的操作报告；密钥不进入聊天、日志或 Git。
- 已有服务器管理的 FinBot 分厂商 Key 文件仅同步新 Key 值，保留其他内容；不自动修改其他应用的生产配置。系统 bootstrap Key、厂家账号/凭据和历史用量记录不属于分发 Key 清理对象。

## 验收与恢复边界

- 管理列表/数据库恰好 8 个 Key，每个厂商恰好一个，33 个旧 ID 及关联权限无残留。
- 新 Key 的模型目录认证成功且不返回其他厂商模型；跨厂商目录请求为 403；已知旧 Key 为 401；旧认证缓存失效。
- ENV/JSON 与生产 Key 的 ID、SHA-256 一致，只有当前用户、SYSTEM、Administrators 可访问密钥文件。
- 旧客户端必须更换凭据；Qwen 创建 Key 不代表有可用账号。删除后的旧 Key 不作为回滚策略，如新 Key 异常则通过 Admin API 撤销并补发，更新受限文件。
- 无源码、数据库结构、镜像或部署修改，版本继续为 0.25.7；结果报告只含 ID、权限、状态与文件路径。

## 2026-10-03 执行结果

- 通过 Admin API 删除全部 **33** 个旧 Key，创建 **8** 个新 Key；管理列表及数据库逐厂商核对为每家 **1** 个、enabled=true、all_models=true，协议/features/transportMode/有效期符合上文。
- 八个新 Key 的 direct/public 目录认证全部 200，目录仅返回所属厂商，跨厂商全部 403。ENV/JSON 与生产 ID、SHA-256 一致。
- 33 个旧 ID 在 api_keys 及 provider/model/protocol/feature grants 中残留均为 0；旧 Redis 认证缓存残留为 0。可取得明文的六个旧 Key 先验 200、删除后在两个入口均为 invalid_api_key 401，共 12 项。
- 删除前旧 Key 关联 gateway_responses / provider_response_states 均为 0，不发生旧存储 Responses 的级联损失；历史 usage_events **868 → 868**，未删除用量历史。
- 新 ENV/JSON、轮换报告及同步后的 FinBot 本地分厂商 ENV 均设置受保护 ACL，只允许当前用户、SYSTEM、Administrators。密钥明文保存在服务器管理私密目录，不进入本仓库。
- 非敏感操作明细：服务器管理 `private/any2api/distribution/rotation-report.json`；本工作区 `backend/build/key-reset-result.json` / `key-reset-operation.log`。生产 Argo Synced/Healthy、四组件 Ready、restart=0，仍为 0.25.7。
- FinBot 本地六个厂商变量同步新值，不代表 FinBot 或其他调用端的生产配置已更新。旧客户端必须更换 Key；Qwen 本次只完成授权创建，不改变账号可用性。
