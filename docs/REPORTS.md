# 数据库订单报表

第一版提供真实 H2 查询、分页预览、商品/状态/下单日期汇总及 `.xlsx` 下载。表内数据为演示数据。当前的客服订单工具仍使用原来的内存模拟订单，报表使用独立的 `report_orders` 表，尚未连接真实订单系统。

## 体验

启动应用后访问 [订单报表](http://localhost:18080/reports.html)，或点击客服页面左侧“订单报表与 Excel 导出”。默认日期为 2026-10-01 至 2026-10-31，点击“查询并生成报表”。不调用大模型；应用启动仍需要原来的模型配置。

当前 application.yml 默认身份为 service，仅看到 A1001：1 笔、1 件、299 元。使用 `DEMO_ACCOUNT=buyer` 重启后，可查看 A1001/A1002/A1003：3 笔、4 件、676 元；已签收 2 笔、498 元。warehouse 仅 A1002：1 笔、2 件、178 元。B2001 对三个账号均不可访问。每页选 2 条可体验买家明细分页。状态筛选“已发货”可体验空结果。

聊天示例：“生成2026年10月的订单报表，按商品汇总并导出Excel”。后端先路由到 order-report，模型加载技能并调用 queryOrderReport，最终回答下方显示真实工具结果对应的报表按钮。历史消息可展开“本轮路由与工具调用”加载关联按钮。报表工具已生成快照但最终模型回答失败时，也可在请求执行记录的工具结果中找到 previewUrl；不会自动重复执行失败的聊天请求。

聊天用到真实模型，消耗供应商额度；测试使用模拟模型，验证工具调用链，不代表真实模型语义选择质量。日期不明时技能要求追问；“本月/上月”根据北京时间日期换算。

2026-10-28 本机联调已使用当前配置的真实模型，完成上述示例的 SSE 对话：loadSkill → queryOrderReport → 最终回答，并在浏览器确认报表按钮与 Excel 下载成功。当前客服身份返回 1 笔、299 元。该次联调不代表所有自然语言表达都已验证。

## 查询与导出

```http
POST /api/reports/orders
Content-Type: application/json

{
  "startDate": "2026-10-01",
  "endDate": "2026-10-31",
  "status": "",
  "productKeyword": "",
  "groupBy": "PRODUCT"
}
```

返回 reportId、筛选条件、数据范围、summary、groups、前10条 rows、previewUrl 和 downloadUrl。日期为下单日期，包含首尾日期。status 支持空值（全部）、DELIVERED、PENDING_SHIPMENT、SHIPPED、CANCELLED；groupBy 支持 PRODUCT、STATUS、DAY。

- `GET /api/reports/orders/{id}?page=0&size=10`：读取同一快照的分页，size 为 1–100。
- `GET /api/reports/orders/{id}/download`：完整 Excel，含“报表说明与汇总”和“订单明细”，不受预览分页影响。
- 快照生成后保存在数据库，刷新或重启后可用原 URL 打开；读取和下载都校验当前账号归属，其他账号返回 404。

一笔订单一行，金额为订单总额（人民币），不再乘商品数量。全部状态会包含取消订单，当前总金额不等于实收或净销售额。汇总由 Java 对完整的有界结果计算，模型只负责说明。Excel 金额为数字、商品名称为文本，不执行字符串公式。

SQL 使用参数绑定，关键词按字面子串处理，% 和 _ 不扩大查询范围。数据权限由后端身份添加，用户不能提交 SQL、表名或角色。报表只从数据库读订单，额外写入报表快照；不修改订单。

单次查询最大 366 天、10000 笔；超过行数会拒绝并要求缩小范围，不生成截断报表。首版同步生成，尚无异步队列、快照保留期清理或真实登录系统。接生产数据前，应将固定演示权限范围换成登录用户的业务授权，并按容量增加异步导出和清理策略。

## 控制台学习日志

默认 INFO 级别即可查看关键步骤，无需打开供应商 HTTP 请求日志。在 IDEA 控制台搜索“报表”可跟踪参数、权限范围、查询行数、快照编号、汇总金额及 Excel 文件生成耗时；搜索 requestId 可跟踪同一轮聊天，reportId 可关联快照读取和下载。

页面生成的顺序为：报表HTTP入口 → 报表生成开始 → 报表权限过滤 → 报表数据库查询完成 → 报表汇总与快照保存完成。这个入口不调用模型。

聊天生成的顺序为：客服业务路由 → 客服模型调用开始 → 客服模型返回（请求 loadSkill）→ 客服工具执行开始/完成 → 下一轮模型请求 queryOrderReport → 报表工具调用 → 查询和保存快照 → 报表工具返回模型 → 最后一轮模型生成回答 → 客服回答保存完成。模型日志区分普通/SSE流式模式，并记录轮次、可用工具、返回工具、Token 用量（供应商未提供时为 null）和耗时。

点击下载会看到：报表快照读取 → 报表Excel导出开始 → 报表Excel文件生成完成。文件生成完成表示服务器已生成字节，不代表浏览器已经保存成功。无效条件或权限拒绝用 WARN，运行异常用 ERROR。

完整模型回答和工具结果放在 DEBUG。需要深入查看时，在 IDEA 的 Program arguments 添加 `--logging.level.com.example.ai.service.CustomerChatService=DEBUG`。默认不输出完整明细、技能正文或模型 HTTP 认证信息。

## 文件与验证

Flyway `V5__order_reports.sql` 仅新增报表表和演示订单，不清空现有聊天记录。ReportService 负责查询、权限、条件和快照；ReportExportService 使用 Apache POI 生成文件；ReportController 为页面和下载提供接口；ReportTools 接入原工具循环。

```powershell
$env:JAVA_HOME = 'C:\Users\wzk\.jdks\jbr-17.0.14'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn '-Dmaven.repo.local=.m2/repository' test
node --test scripts/frontend-check.cjs scripts/report-frontend-check.cjs
```

测试覆盖筛选、日期首尾、金额口径、分页总计、三种角色、快照归属、查询限额、源数据变化后的导出一致性、真实 xlsx 读取、技能限制和聊天重试去重。测试库隔离，不读取用户 data 目录。
