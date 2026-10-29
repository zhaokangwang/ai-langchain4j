# 多角色技能：第一阶段

报表扩展：新增 order-report，当前注册技能共 10 个，买家/客服/仓库分别可见 6/8/3 个技能。application.yml 当前默认身份为 service。下文保留第一阶段记录；报表及 V5 迁移见 [REPORTS.md](REPORTS.md)。

后续进度：第二阶段已接入问题＋会话状态路由、少量候选、按加载技能收缩工具和歧义追问，详见 [SKILL-ROUTING.md](SKILL-ROUTING.md)。下面保留第一阶段的角色、注册表和启动配置说明。

已实现：后端演示身份、九个结构化技能、按角色过滤目录和工具、技能及工具执行校验、模拟数据范围、会话及记录归属隔离。普通 JSON 与 SSE 使用同一 CustomerChatService，无数据库迁移或数据清空。

## 选择身份

本阶段是启动配置身份，每个进程只有一个当前账号。不是登录系统，也没有客户端角色切换接口。不要以请求头、问题中的“我是管理员”等文字作为身份依据。后续正式多用户登录应改为从经过认证的安全上下文获取身份，并为异步执行显式传递身份快照。

IDEA Run Configuration 的 Environment variables 增加 `DEMO_ACCOUNT=buyer`、`DEMO_ACCOUNT=service` 或 `DEMO_ACCOUNT=warehouse`，保留原 DEEPSEEK_API_KEY，然后重启应用。也可以在 Program arguments 添加 `--app.demo.account=service`。不支持的账号值将阻止启动。使用同一个文件数据库时，依次停止、切换、重启，不启动多个进程争用文件。

| 配置值 | 账号 | 数据范围 | 可见技能数 |
| --- | --- | --- | --- |
| buyer（默认） | demo-user / BUYER | 本人 A1001、A1002、A1003 | 5 |
| service | demo-service / CUSTOMER_SERVICE | 分配工单 AS2001 及关联订单 A1001 | 7 |
| warehouse | demo-warehouse / WAREHOUSE | 所属仓库 WH01 及订单 A1002 | 2 |

访问 `GET /api/demo/capabilities` 查看当前后端身份和授权技能元数据，不调用模型，也不返回未授权目录。前端仍沿用原聊天页面，身份细节、路由进度和交互在第三阶段接入。

## 九个技能

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

`src/main/resources/skills/registry.json` 是授权注册表，包含 name、category、description、notFor、allowedRoles、allowedTools；对应目录的 SKILL.md 是正文。注册时验证名称、重复项、正文、角色和工具名称。模型首次只获得当前角色的目录摘要；loadSkill 再次校验权限后才返回正文。路径和角色不接受模型指定。

第一阶段工具范围取“当前角色全部授权技能的工具并集”；第二阶段已进一步限制为本轮候选中已加载技能的工具。模型生成文字本身不构成业务授权，实际访问由 Java 校验。

## 模拟业务与示例问题

- 买家：问“订单 A1001 能退吗”“A1001 的退款到哪一步了”。RF3001 为模拟待人工审核退款记录。问“审核 AS2001”或“查仓库库存”不具备对应技能权限；模型即便请求这些工具也不能取得数据。
- 客服：问“检查 AS2001 缺哪些材料”。模拟工单已有商品照片、缺故障视频，配件完整性尚未核实。AS2002 不在分配范围，不能查询。A1002 同样不在当前客服授权范围。
- 仓库：问“查询 WH01 缺货情况”“为什么 A1002 没发货”。模拟无线鼠标可用库存 0、待发货需求 1；只提供复核建议，不创建采购、调拨或发货。
- 模拟订单 B2001 对三个演示身份均不可访问。不存在与无权访问订单/工单统一返回未找到，不能由用户提供其他 owner 或 warehouse 参数扩大范围。

这些问题用于实际模型联调时会消耗模型额度。语义选择由模型负责，不保证每次选择相同调用顺序；工具权限拒绝为后端确定性逻辑。

## 保存与兼容

conversations.owner_id 改用当前后端账号；默认买家继续使用 demo-user，旧记录仍保留。切换角色后，最近会话列表和消息、执行记录只允许读取当前账号数据；前端缓存旧 conversationId 时会按不可访问处理。不能通过复用其他账号 requestId 读取完成结果或追加上下文。

每次新执行写入 AUTHORIZATION 事件，记录账号、角色、数据范围和授权技能名单，可在现有执行记录面板查看。被拒绝的技能/工具调用记录为 FAILED，不返回技能正文。原旧售后示例接口仍保留，使用独立的 order-after-sales-review 教学文件，不计入统一聊天的九个注册技能；该接口不查询订单，仅评估用户传入材料，仓库身份禁止访问。原 OrderTools 查询也遵守当前账号的订单范围。

## 验证

SkillAuthorizationTest 验证九个技能的角色集合、正文授权、非法路径、伪造角色请求头无效、数据范围、模型越权调用拦截、跨账号会话和幂等结果隔离，以及客服和仓库技能的正常执行。其他测试继续覆盖旧接口、文件数据库重启、SSE 和实际 HTTP 传输。测试使用模拟模型或本地 HTTP 模拟供应商，不访问真实 DeepSeek。
