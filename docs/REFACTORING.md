# 职责分层重构记录

2026-10-29 按 AGENTS.md 与项目 Checkstyle 约束完成现有后端代码的职责整理。

## 主要变化

| 原有情况 | 当前实现 |
| --- | --- |
| Controller 内的 record 被 Service、Repository 引用 | 共享契约归入 chat、aftersales、history、report 业务包 |
| HistoryService 中混合 SQL、校验和事务 | HistoryRepository 承载 SQL，Service 保留权限、分页及修改事务 |
| ReportService 中混合 SQL 和报表业务 | OrderReportRepository 承载绑定参数的查询和快照存取，Service 保留数据范围、校验、汇总与分页 |
| 模型适配器和聊天服务直接写模型、工具和事件表 | ExecutionAuditRepository 统一执行审计 SQL |
| 聊天服务内嵌工具权限、参数解析与执行 | ChatToolExecutor 独立执行每次工具的授权、参数、对象范围校验和审计编排 |
| SkillRouter.route 圈复杂度 70 | 分步路由，所有方法统一适用复杂度 30 上限，删除全部存量豁免 |
| Controller 直接查询身份和技能能力 | DemoCapabilitiesService 提供既有响应内容 |
| 启动恢复逐条查询、逐条写审计 | 每批最多 500 条，按 requestId 游标读取，JDBC batchUpdate；同一恢复事务提交事件与状态 |

生产组件全部通过构造器注入依赖，复用 Spring 管理的 ObjectMapper；文件重启和角色隔离测试的手工装配集中在 TestChatServices，不保留为测试拼装依赖的生产构造器。

## 保持的契约

- HTTP 路径、状态码和响应结构，20 个已有公开 record 的字段与校验声明。
- SSE 事件、取消与中断语义，完成事件仍在结果持久化后发送。
- 三种演示角色的数据范围，技能名称、注册表及每次工具执行的授权校验。
- requestId 去重、已完成结果重放、失败重试、禁止中断请求自动重放及成功上下文恢复。
- 报表的 10000 条上限、字面关键词匹配、完整结果汇总、快照分页和完整快照导出。
- 原有 Maven 依赖、Flyway 迁移、配置、技能正文和前端资源。

## 验证

- `mvn '-Dmaven.repo.local=.m2/repository' clean verify`：从空构建目录编译 35 个主源码、12 个测试源码；0 个 Checkstyle 违规，59 个后端测试全部通过。
- 新增批量恢复测试：1005 条未完成请求跨三个批次，重复恢复不重复写事件，已完成请求保持完成状态，不调用模型。
- `node --test scripts/frontend-check.cjs scripts/report-frontend-check.cjs`：20 个前端行为测试通过。
- 应用 JavaScript 的 `node --check` 通过。
- 临时违规源码验证 controller、service、model、report、repository、skill、tool 七个包的依赖限制确实能阻断 Maven；清除临时源码后 `validate` 恢复为 0 违规。
- 对比重构前源码快照，核对 20 个公开 record 声明，以及 Maven POM 和全部运行时资源；检查干净构建 JAR 中共享 DTO 与新组件存在、旧内部 DTO 不残留。

测试使用 mock 或本地模拟模型供应商、隔离内存库及 JUnit 临时文件库；未调用真实模型或操作用户数据库。
