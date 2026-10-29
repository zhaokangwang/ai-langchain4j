package com.example.ai.tool;

import com.example.ai.security.DemoIdentity;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/** 教学用只读工具：模拟数据，后续可将查询部分替换为业务 Service。 */
@Component
public class OrderTools {
    private final DemoIdentity identity;
    public OrderTools(DemoIdentity identity) { this.identity=identity; }
    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);
    private static final Map<String, OrderResult> ORDERS = Map.of(
            "A1001", new OrderResult(true, "A1001", "机械键盘", "已发货", "顺丰速运", "SF-DEMO-1001", "模拟订单数据"),
            "A1002", new OrderResult(true, "A1002", "无线鼠标", "待发货", "", "", "模拟订单数据"));

    @Tool("根据订单号查询模拟订单的商品、状态和物流信息。用户询问具体订单时使用；没有订单号时先询问，不要猜测订单号。此工具只查询，不修改或取消订单。")
    public OrderResult queryOrder(@P("用户提供的订单号，例如 A1001") String orderNumber) {
        log.info("执行订单查询工具 queryOrder");
        if (orderNumber == null || !orderNumber.trim().matches("(?i)A[0-9]{4}")) {
            return new OrderResult(false, "", "", "", "", "", "订单号格式无效，请提供 A 加四位数字，例如 A1001");
        }
        String normalized = orderNumber.trim().toUpperCase(Locale.ROOT);
        if (!identity.canAccessOrder(normalized)) return new OrderResult(false, normalized, "", "", "", "", "未找到可访问的模拟订单");
        return ORDERS.getOrDefault(normalized,
                new OrderResult(false, normalized, "", "", "", "", "未找到该模拟订单"));
    }

    public record OrderResult(boolean found, String orderNumber, String product,
                              String status, String carrier, String trackingNumber, String note) {}
}
