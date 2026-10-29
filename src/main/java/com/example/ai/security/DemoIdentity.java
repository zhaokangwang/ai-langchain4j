package com.example.ai.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 身份由启动配置决定，不从聊天正文、模型参数或请求头读取。 */
@Component
public class DemoIdentity {
    public enum Role { BUYER, CUSTOMER_SERVICE, WAREHOUSE }
    public record Account(String id, Role role, String scope) { }
    private final Account account;
    public DemoIdentity(@Value("${app.demo.account:buyer}") String name) {
        account = switch (name) {
            case "buyer" -> new Account("demo-user", Role.BUYER, "本人订单");
            case "service" -> new Account("demo-service", Role.CUSTOMER_SERVICE, "分配工单 AS2001 的关联订单 A1001");
            case "warehouse" -> new Account("demo-warehouse", Role.WAREHOUSE, "仓库 WH01 的订单 A1002");
            default -> throw new IllegalArgumentException("app.demo.account 仅支持 buyer、service、warehouse");
        };
    }
    public Account current() { return account; }
    public String ownerId() { return account.id(); }
    public boolean canAccessOrder(String number) {
        return switch (account.role()) {
            case BUYER -> java.util.Set.of("A1001", "A1002", "A1003").contains(number);
            case CUSTOMER_SERVICE -> "A1001".equals(number);
            case WAREHOUSE -> "A1002".equals(number);
        };
    }
}
