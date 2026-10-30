# 多角色技能与登录授权

账号功能第四批已把 Session 登录角色接入 Skill、工具和共享数据。页面顶部登录账号与能力面板角色一致，同角色共享会话/快照，跨角色由后端拦截。异步快照、账号复核与兼容说明见 [ROLE-SHARING.md](ROLE-SHARING.md)；账号管理见 [ACCOUNTS.md](ACCOUNTS.md)。

报表扩展：新增 order-report，当前注册技能共 10 个，买家/客服/仓库分别可见 6/8/3 个技能。角色取自登录账号；报表及 V5 迁移见 [REPORTS.md](REPORTS.md)。

业务路由已接入问题＋会话状态、少量候选、按加载技能收缩工具和歧义追问，详见 [SKILL-ROUTING.md](SKILL-ROUTING.md)。

已实现：登录身份快照、十个结构化技能、按角色过滤目录和工具、技能及工具执行校验、模拟数据范围、角色共享会话与记录。普通 JSON 与 SSE 使用同一 CustomerChatService，本批不迁移或清空已有数据。

## 选择身份

管理员创建账号时选择 buyer、service、warehouse。用户登录后由服务端账号记录决定角色，不能以请求头、请求参数或“我是管理员”等聊天文字切换身份。管理员维护权限与业务角色分开，不默认获得全部技能。

切换角色请退出后登录另一个角色账号，无需重启；启动配置 DEMO_ACCOUNT/app.demo.account 不再决定身份。Spring 管理的 DemoIdentity 必须读取认证快照，无匿名回退；固定字符串构造器只用于显式教学实例及隔离测试。SSE 将快照带入工作线程并在退出时清理。

| 登录角色 | 共享分区及枚举 | 数据范围 | 可见技能数 |
| --- | --- | --- | --- |
| buyer | demo-user / BUYER | 角色共享 A1001、A1002、A1003 | 6 |
| service | demo-service / CUSTOMER_SERVICE | 角色共享工单 AS2001 及关联订单 A1001 | 8 |
| warehouse | demo-warehouse / WAREHOUSE | 角色共享仓库 WH01 及订单 A1002 | 3 |

访问 `GET /api/demo/capabilities` 查看当前后端身份和授权技能元数据，不调用模型，也不返回未授权目录。前端仍沿用原聊天页面，身份细节、路由进度和交互在第三阶段接入。

## 技能目录

| 技能 | 业务范围 | 角色 |
| --- | --- | --- |
| order-query | 订单信息 | 买家、客服 |
| logistics-tracking | 物流信息 | 买家、客服 |
| delivery-exception-guide | 物流异常建议 | 买家、客服 |
| return-policy-guide | 退货条件与材料咨询 | 买家、客服 |
| after-sales-review | 已分配工单审核建议 | 客服 |
| after-sales-material-check | 已分配工单材料检查 | 客服 |
| refund-progress-query | 已有退款进度 | 买家、客服 |
| warehouse-stock-check | 当前仓库库存 | 仓库 |
| shipment-exception-review | 当前仓库发货异常建议 | 仓库 |
| order-report | 角色范围内的订单报表、快照和下载 | 买家、客服、仓库 |

`src/main/resources/skills/registry.json` 是授权注册表，包含 name、category、description、notFor、allowedRoles、allowedTools；对应目录的 SKILL.md 是正文。注册时验证名称、重复项、正文、角色和工具名称。模型首次只获得当前角色的目录摘要；loadSkill 再次校验权限后才返回正文。路径和角色不接受模型指定。

第一阶段工具范围取“当前角色全部授权技能的工具并集”；第二阶段已进一步限制为本轮候选中已加载技能的工具。模型生成文字本身不构成业务授权，实际访问由 Java 校验。

## 模拟业务与示例问题

- 买家：问“订单 A1001 能退吗”“A1001 的退款到哪一步了”。RF3001 为模拟待人工审核退款记录。问“审核 AS2001”或“查仓库库存”不具备对应技能权限；模型即便请求这些工具也不能取得数据。
- 客服：问“检查 AS2001 缺哪些材料”。模拟工单已有商品照片、缺故障视频，配件完整性尚未核实。AS2002 不在分配范围，不能查询。A1002 同样不在当前客服授权范围。
- 仓库：问“查询 WH01 缺货情况”“为什么 A1002 没发货”。模拟无线鼠标可用库存 0、待发货需求 1；只提供复核建议，不创建采购、调拨或发货。
- 模拟订单 B2001 对三个演示身份均不可访问。不存在与无权访问订单/工单统一返回未找到，不能由用户提供其他 owner 或 warehouse 参数扩大范围。

这些问题用于实际模型联调时会消耗模型额度。语义选择由模型负责，不保证每次选择相同调用顺序；工具权限拒绝为后端确定性逻辑。

## 保存与兼容

conversations.owner_id 和报表快照 owner_id 使用三个角色分区 ID，旧记录继续保留。同角色可读取和继续共享会话及复用完成结果；跨角色列表、消息、执行记录、快照及下载均受范围限制，不能复用其他角色 requestId 取得结果或追加上下文。新 AUTHORIZATION 另记实际发起账号 actor，旧事件不推测补齐。

每次新执行写入 AUTHORIZATION 事件，记录账号、角色、数据范围和授权技能名单，可在现有执行记录面板查看。被拒绝的技能/工具调用记录为 FAILED，不返回技能正文。原旧售后示例接口仍保留，使用独立的 order-after-sales-review 教学文件，不计入统一聊天的九个注册技能；该接口不查询订单，仅评估用户传入材料，仓库身份禁止访问。原 OrderTools 查询也遵守当前账号的订单范围。

## 验证

SkillAuthorizationTest 验证九个技能的角色集合、正文授权、非法路径、伪造角色请求头无效、数据范围、模型越权调用拦截、跨账号会话和幂等结果隔离，以及客服和仓库技能的正常执行。其他测试继续覆盖旧接口、文件数据库重启、SSE 和实际 HTTP 传输。测试使用模拟模型或本地 HTTP 模拟供应商，不访问真实 DeepSeek。
