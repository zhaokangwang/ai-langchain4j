# 登录角色、Skill 与共享数据

第四批已把已认证账号接入业务身份。业务角色只有 buyer、service、warehouse，管理员标记仅开放账号管理，不额外放大 Skill 或订单权限。启动参数 `DEMO_ACCOUNT` / `app.demo.account` 不再决定运行时业务身份。

## 角色与数据分区

| 登录角色 | 共享分区 ID | 可见 Skill 数 | 演示业务范围 |
| --- | --- | --- | --- |
| buyer | demo-user | 6 | A1001、A1002、A1003 订单及相应买家技能 |
| service | demo-service | 8 | 共享 AS2001 工单及关联订单 A1001 |
| warehouse | demo-warehouse | 3 | 共享 WH01 库存及订单 A1002 |

Skill 名称、注册表、工具白名单和业务对象规则沿用现有实现。业务订单范围可以重叠，例如买家和客服都可以查询 A1001，但两者的会话、执行记录及报表快照仍在不同角色分区。B2001 对三个角色均不可见。

同角色账号可以读取和继续对方会话、查看执行记录、重命名或归档/恢复会话、复用已完成的 requestId 结果、读取或下载同角色报表，并取消同角色正在生成的 SSE 请求。跨角色历史及快照返回 404，跨角色 requestId 重用保留原 409 冲突语义，跨角色取消和不存在的请求都返回 cancelRequested=false。

这些是有意设计的测试业务共享范围；本批不实现账号级数据隔离。旧的 demo-user/demo-service/demo-warehouse 记录直接沿用，不改 ID、不清库、不修改已应用迁移。报表订单原 owner_id=demo-user 是演示数据标识，查询范围仍由后端固定角色规则决定，不能通过用户或模型传入 owner/role 扩大范围。

## 身份与执行边界

AccountSessionFilter 从数据库复核账号后，为本次请求创建不可变 BusinessIdentitySnapshot（账号 ID、Session 版本、用户名和角色），放入请求的安全上下文。DemoIdentity 保留类名和 Role 枚举以兼容现有组件，但 Spring 管理的实例只接受此快照，无匿名或启动配置回退。固定字符串构造器仅供显式教学实例和隔离测试，不是生产身份入口。

AccountSessionAccess 只通过 AccountRepository 访问账号状态，供登录服务与业务执行共同复用；身份组件不反向调用 Service，SQL 仍在 Repository。HTTP、工具、历史重放、报表和下载继续通过 Service 校验范围。请求参数、请求头及模型自称身份不能改写角色，提示词不代替后端授权。

SSE 在线程池提交前通过 CustomerChatService.prepareChat 捕获快照，工作线程建立独立上下文，成功或失败后都清理并恢复原上下文。不使用可变单例字段或可继承线程身份传递用户选择，线程复用不会沿用前一个用户的角色。

在聊天开始、模型轮次、工具执行和最终回答提交前重新核验账号有效性。流式模型等待期间由事件消费线程约每秒核验账号启用状态、Session 版本、角色和待改密状态；模型回调线程仍只写有界队列。发现失效时取消上游、进入 ChatStream.Interrupted 流程，落库 INTERRUPTED 并通过原 error 事件返回 status=499，不发送 done，不提交最终助手消息或成功上下文。已经发出的片段和已完成操作无法撤回；取消仍是尽力执行。

普通 JSON 在模型返回后发现账号失效时返回相应 HTTP 错误，保留原失败落库流程；不会执行后续工具或保存最终回答。数据库事务仍不覆盖模型等待、SSE 生命周期或 Excel 生成。

注销只使当前 HTTP Session 失效；页面导航会断开当前连接。后台已受理请求的账号复核以启用状态、版本和角色为准，不承诺撤回已完成的模型调用。禁用、管理员重置及本人改密都会更新账号状态或版本，等待中的流会在下次复核时中断。

## 展示与审计兼容

GET /api/demo/capabilities 保留路径和已有 account/skills/identitySource 字段，新增 actor 表示实际登录账号；account.id 继续表示角色共享分区，account.role 保持原大写枚举，/api/auth/me.role 保持小写业务代码。

统一聊天的 AUTHORIZATION 审计和 SSE authorization 事件保留 account/skills，并增加不含凭据的 actor（id、username、role）。历史按尝试读取原记录，不用当前账号覆盖历史发起人；旧事件没有 actor 时不推测补齐。完成结果重放不改写原执行人的审计。

页面说明改为登录角色与共享分区。同角色能力刷新不会清空当前共享会话，跨角色刷新会清除旧视图并重新读取列表。登录、退出或本人密码变化通过无凭据的 BroadcastChannel 通知其他标签页刷新，避免保留之前角色的页面视图；不支持通知的浏览器仍由后端检查每次请求。

## 验证

```powershell
mvn '-Dmaven.repo.local=.m2/repository' '-Dtest=RoleAccessIntegrationTest,RoleStreamingIntegrationTest' test
mvn '-Dmaven.repo.local=.m2/repository' verify
node --test scripts/frontend-check.cjs scripts/report-frontend-check.cjs scripts/auth-frontend-check.cjs scripts/admin-accounts-frontend-check.cjs
```

新增 15 个后端测试覆盖真实登录的三个角色、同角色会话和报表共享、跨角色读取/重放/取消拦截、模型越权工具拒绝、旧数据兼容、无身份拒绝、JSON 执行中账号失效、SSE 并发身份、线程清理和等待中禁用/版本/角色变化中断。测试使用隔离内存库及模拟模型，不访问用户 data/ 或收费模型。既有业务测试显式使用认证测试账号，并为异步调用显式捕获身份，没有为生产代码增加固定身份回退。

2026-10-30 验证结果：JDK 17.0.14、Maven 3.9.9，角色专项 15 项通过；最终完整 verify 成功，105 个后端测试通过，0 失败/错误/跳过，Checkstyle 为 0 违规，打包成功。四份 Node 前端行为测试共 39 项通过，全部应用脚本通过 node --check，git diff --check 未发现空白错误。本次未进行真实模型联调或浏览器点击验收，也未启动连接用户数据库的应用实例。

第五批增加随机端口的真实 HTTP 全流程测试，三个角色的 Skill、同角色历史/重放/报表共享、跨角色拦截，以及禁用、启用、重置后的登录失效流程均通过。首次发现的 OpenAPI 声明问题已修复，专项重跑通过，最终完整 verify 成功：106 个后端测试全部通过，Checkstyle 0 违规，打包成功。实际浏览器点击仍因工具环境故障未执行。第五批具体状态和命令见 [ACCOUNT-DELIVERY.md](ACCOUNT-DELIVERY.md)。
