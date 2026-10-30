# 项目代码组织

Java 根包为 com.example.ai，AiApplication 留在根包以扫描下级组件。项目使用职责分层，并将智能体相关能力单独分包。

```text
com.example.ai
├── AiApplication.java
├── controller/  HTTP 接口、参数校验、SSE 连接及下载响应
├── chat/        共享聊天请求/响应 record
├── aftersales/  售后请求、上下文和响应 record
├── history/     历史查询及修改的共享契约
├── account/     账号内部记录；HTTP 响应另行定义
├── report/      报表业务、导出及共享数据契约
├── service/     聊天编排、受控工具执行、售后、历史及能力服务
├── repository/  聊天状态、历史查询、报表和执行审计的 JDBC 持久化
├── model/       大模型适配、调用审计、流式兼容和旧 AI Service 示例
├── skill/       技能注册、业务路由、单次请求的技能执行范围
├── tool/        受控业务工具和模拟数据
├── security/    Session 有效性校验、登录角色快照及共享数据范围规则
├── stream/      SSE 连接执行控制与取消状态
└── config/      OpenAPI、HTTP 安全链、密码编码与管理员启动初始化配置
```

请求由 Controller 进入 Service，再由 Repository 访问数据库。跨层共享 record 按业务归入 chat、aftersales、history、report；Service/Repository 不再反向引用 Controller 内部类型。工具自身的返回 record 仍可随工具保留，不增加全局杂项 DTO 包。

账号请求/响应及内部记录归入 account。AuthenticationController 负责登录会话边界，AccountAdministrationController 负责管理接口绑定；AuthenticationService 处理登录和本人改密，AccountAdministrationService 编排管理事务，AccountRepository 承担账号 SQL。AccountSessionAccess 复用账号有效性校验，不反向调用 Service。AccountSessionFilter 为请求建立 BusinessIdentitySnapshot，DemoIdentity 从快照取得角色共享分区；CustomerChatService.prepareChat 将快照显式传入 SSE 工作线程并在退出时清理。

CustomerChatService 管理请求/会话锁、授权与路由事件和有界模型循环；ChatToolExecutor 逐次验证工具名称、技能、对象归属与参数，并记录工具结果。SkillRouter 将对象识别、候选召回、报表、归一化、上下文追问及最终决策拆成独立步骤，每次请求的选择状态只存局部对象。

ChatHistoryRepository 保留短事务、完成结果的原子提交、请求去重及恢复；HistoryRepository 负责历史查询和更新，事务与业务校验由 HistoryService 控制；OrderReportRepository 负责有界订单查询和快照存取；ExecutionAuditRepository 负责模型、工具及请求操作事件写入。模型适配器只负责模型调用、协议适配和审计编排，不直接执行 SQL。

Checkstyle 通过 import-control 锁定上述依赖方向，复杂度不再有存量例外。详细限制及验证命令见 CODE-QUALITY.md。

前端仍在 src/main/resources/static，技能正文和注册表在 resources/skills，Flyway 在 resources/db/migration。测试保留在根测试包并显式导入被测类，便于作为跨层集成测试阅读。

职责重构保持 HTTP 路径、JSON 字段及分页、技能名称、角色数据范围、SSE 事件和数据库迁移历史。IDEA 如显示旧类缓存，重新加载项目并重新编译；启动入口仍是 com.example.ai.AiApplication。共享类型移动后交付前执行干净构建，避免旧内部 record 的 class 文件残留在 jar。
