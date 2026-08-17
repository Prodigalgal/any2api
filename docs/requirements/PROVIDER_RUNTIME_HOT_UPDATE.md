# 厂商浏览器运行时规则热更新

## 目标

在不重新构建 Backend、Automation 或 WEB 镜像的前提下，调整 MiMo、GLM 官方页面的
模块发现标记、能力导出名、允许的端点路径和会话 canary 参数。Java 只传递稳定的语义
命令，Automation 负责把语义命令映射成当前厂商页面需要的请求。

## 范围

- MiMo、GLM 使用 `operation + semanticCommand`，不再由 Java 生成厂商请求 body。
- PostgreSQL 保存不可变规则 revision、候选状态、当前生效 revision 和 last-known-good。
- Automation 对候选规则先执行 build discovery，再执行真实 operation；只有 operation 成功
  才允许 Backend 原子晋升候选规则。
- 官方页面静态资源集合生成 `buildId`，会话超过规则 TTL、规则 revision 变化或 build 变化时
  重新发现运行时。
- 管理端提供候选创建、候选废弃、历史查看和历史 revision 回滚。

## 非目标

- 不允许热更新任意 JavaScript、正则表达式、请求头、Cookie 或签名实现。
- 不在 canary 失败时把失败请求重放到其他规则，避免重复创建对话或重复计费。
- 不迁移尚未纳入 official-browser runtime 的 provider；旧 transport 字段继续兼容 MinMax
  等既有链路。
- 不用模型发现、keepalive 或只读页面打开替代真实推理 operation 的晋升证据。

## 规则契约

规则只包含以下受限字段：

- `schemaVersion`：当前固定为 `1`。
- `sessionMaxAgeSeconds`：官方页面会话最大复用时间。
- `canaryTimeoutSeconds`：静态资源加载与模块发现的超时上限；真实推理沿用 provider 的
  长流超时，避免 canary 参数截断长回答。
- `buildAssetMarkers`：用于筛选官方静态资源 URL 的字面量标记。
- `discoveryMarkers`：按语义角色分组的源码字面量标记。
- `capabilities`：已发现模块的受限导出属性名。
- `endpointPaths`：按语义 operation 配置的同源相对路径。

所有字符串均限制长度、数量和控制字符；端点必须是相对路径。规则没有可执行能力。

## 状态与一致性

1. 管理员提交规则后生成新的不可变 revision，状态为 `PENDING`。
2. Backend 在每个 semantic command 中附加 active rule 和可选 candidate rule。
3. Automation 优先以 candidate 做 build discovery；失败时上报 `FAILED`，在尚未发起上游
   operation 的前提下使用 active rule。
4. candidate 的真实 operation 成功后上报 `PASSED`；Backend 通过 revision fencing 原子晋升。
5. 上游认证、限流或网络错误不判定规则失败，candidate 保持 `PENDING`，避免错误归因。
6. 回滚会复制历史规则形成新的 candidate，仍需经过相同 canary，不直接改写 active revision。

## 影响模块

- `backend/runtime`：规则验证、revision、状态机、晋升和回滚。
- `backend/transport/OfficialBrowserTransportClient.java`：semantic command 与 runtime plan 契约。
- `automation/providers/runtime_rules.py`：规则解析、选择、报告与 build ID。
- `automation/providers/mimo_browser.py`、`glm_runtime.py`：语义映射、发现和 canary。
- `web`：中文运行时规则管理页面。
- Liquibase `020`：规则 revision 与 provider 状态表。

## 验收标准

- Java transport payload 中不再出现 MiMo/GLM 的厂商 method、path 或 body。
- Python 单元测试证明同一 semantic command 可由不同 revision 构造厂商请求。
- candidate discovery 失败会保留 active rule，且不会发起两次真实 chat operation。
- candidate 只有在真实 operation 完成后晋升，过期或乱序报告不能覆盖较新状态。
- 规则修改、废弃和回滚无需重新构建镜像；历史 revision 和失败原因可查询。
- Automation 测试、Backend 测试、WEB lint/build 和 Liquibase 启动验证全部通过。

## 回滚

运行时回滚通过选择历史 revision 创建新 candidate，并经过 canary 后晋升。版本级回滚必须
同时回滚 Backend 与 Automation；`020` 表是向后兼容新增结构，可保留并由旧版本忽略。
