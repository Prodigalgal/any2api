# 注册计划、任务分页与中文界面

## 目标

- 管理员可以创建单次或固定间隔循环的注册计划。
- 到期计划幂等地产生普通注册任务，继续使用现有注册、授权和推理就绪链路。
- 注册任务与注册计划均使用服务端分页，历史数据不再受固定 `LIMIT 200` 限制。
- 管理端统一使用科技蓝视觉体系和中文文案；品牌、API、AI、ID、Token、厂商与模型名保留行业惯用写法。

## 范围

- 新增 `registration_schedules` 持久化表、租约调度器和管理 API。
- 新增注册任务分页 API，并保留原列表 API 兼容现有调用方。
- 生命周期页面增加“注册任务 / 定时注册”视图、计划增删改启停和分页。
- 全局 MUI 主题、登录页和导航改为科技蓝，并翻译可见的非专业英文文案。

## 非目标

- 不引入任意 Cron 表达式；循环计划采用分钟级固定间隔，避免时区和 DST 歧义。
- 不改变厂商注册参数、验证码策略、代理亲和、账号状态或 inference readiness 判定。
- 不重写既有历史任务和运行事件。

## 影响文件

- `backend/src/main/java/com/any2api/lifecycle/`
- `backend/src/main/java/com/any2api/api/admin/`
- `backend/src/main/resources/db/changelog/`
- `web/src/components/lifecycle-jobs.tsx`
- `web/src/theme/theme.ts`
- `web/src/components/app-shell.tsx`
- `web/src/components/login-screen.*`
- `web/src/lib/api.ts`

## 验收标准

- 单次计划成功生成一个任务后自动停用；循环计划按固定间隔推进下一次执行时间。
- 同一次计划触发使用稳定 idempotency key，调度重试不会重复创建任务。
- 计划和任务支持厂商、状态、页码和每页条数查询，并返回总数与总页数。
- 生命周期页面无原始枚举文案，表头、网格线、筛选器和分页严格对齐。
- 桌面和 390px 移动端无页面级横向溢出；数据表在自身容器内横向滚动。

## 测试方式

- 后端 Gradle 全量测试与 `bootJar`。
- Web lint、production build。
- Browser 桌面和移动端：分页、视图切换、计划创建/编辑/启停、弹窗与控制台日志。
