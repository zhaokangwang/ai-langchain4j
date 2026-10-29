package com.example.ai.tool;

import com.example.ai.skill.SkillRegistry;
import com.example.ai.security.DemoIdentity;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 统一聊天使用的模拟只读工具。身份来自后端启动配置，不接受模型传入。 */
@Component
public class CustomerTools {
    private final SkillRegistry registry;
    public SkillRegistry registry() { return registry; }
    private final DemoIdentity identity;
    public DemoIdentity identity() { return identity; }
    private final List<DemoOrder> orders = List.of(
            new DemoOrder("demo-user", "A1001", "机械键盘", "已签收", 1, "普通实物商品", "顺丰速运", "SF-DEMO-1001"),
            new DemoOrder("demo-user", "A1002", "无线鼠标", "待发货", null, "普通实物商品", "", ""),
            new DemoOrder("demo-user", "A1003", "蓝牙键盘", "已签收", 3, "普通实物商品", "中通快递", "ZT-DEMO-1003"),
            new DemoOrder("other-user", "B2001", "机械键盘", "已签收", 2, "普通实物商品", "", ""));

    public CustomerTools(SkillRegistry registry, DemoIdentity identity) {
        this.registry=registry; this.identity=identity;
    }

    @Tool("加载当前授权技能目录中的技能正文；不允许使用目录之外的技能。")
    public String loadSkill(@P("当前授权目录中的技能名称") String skillName) {
        return registry.load(skillName, identity);
    }

    @Tool("按商品关键词查找当前身份有权访问的订单。多个候选必须请用户选择，不能猜测。返回模拟数据。")
    public List<DemoOrder> findMyOrders(@P("从用户描述提取的商品关键词，例如 键盘；空字符串表示列出本人订单") String productKeyword) {
        String keyword = productKeyword == null ? "" : productKeyword.trim();
        if (!registry.canUseTool("findMyOrders",identity)) return List.of();
        return orders.stream().filter(o -> identity.canAccessOrder(o.orderNumber()))
                .filter(o -> o.product().contains(keyword)).toList();
    }

    @Tool("按订单号查询当前身份有权访问的订单详情。无权访问与不存在统一返回未找到。数据为模拟数据，不包含质量问题或用户意愿。")
    public Object getMyOrderDetails(@P("已由用户明确提供或选择的订单号") String orderNumber) {
        String number = orderNumber == null ? "" : orderNumber.trim().toUpperCase(Locale.ROOT);
        return orders.stream().filter(o -> identity.canAccessOrder(o.orderNumber()) && o.orderNumber().equals(number))
                .findFirst().<Object>map(o -> o).orElse(Map.of("found", false, "message", "未找到可访问的模拟订单"));
    }

    @Tool("查询当前身份可访问订单的模拟退款进度，不执行退款。")
    public Object getAccessibleRefundProgress(@P("订单号") String orderNumber) {
        String number=orderNumber.trim().toUpperCase(Locale.ROOT);
        if (!registry.canUseTool("getAccessibleRefundProgress",identity) || !identity.canAccessOrder(number)) return Map.of("found",false,"message","未找到可访问的退款记录");
        return number.equals("A1001") ? Map.of("found",true,"orderNumber",number,"refundId","RF3001","status","待人工审核","simulated",true)
                : Map.of("found",false,"message","未找到可访问的退款记录");
    }

    @Tool("读取分配给当前客服的模拟售后工单；无法访问和不存在统一返回未找到。")
    public Object getAssignedAfterSalesTicket(@P("售后工单号") String ticketNumber) {
        if (identity.current().role()!=DemoIdentity.Role.CUSTOMER_SERVICE || !"AS2001".equalsIgnoreCase(ticketNumber.trim()))
            return Map.of("found",false,"message","未找到可访问的工单");
        return Map.of("found",true,"ticketNumber","AS2001","orderNumber","A1001","issue","按键故障（买家描述，未核实）","submittedMaterials",List.of("商品照片"),"faultVideo","未提交","accessoriesComplete","尚未核实","simulated",true);
    }

    @Tool("查询当前仓库人员所属仓库的模拟库存，不接受外部仓库号。")
    public Object getWarehouseStock() {
        if (identity.current().role()!=DemoIdentity.Role.WAREHOUSE) return Map.of("found",false,"message","无仓储查询权限");
        return Map.of("warehouseId","WH01","product","无线鼠标","available",0,"pendingDemand",1,"orderNumber","A1002","simulated",true);
    }

    public record DemoOrder(String owner, String orderNumber, String product, String status,
                            Integer daysSinceDelivery, String productType, String carrier, String trackingNumber) {}
}
