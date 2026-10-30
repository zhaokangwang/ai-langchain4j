package com.example.ai.security;

import com.example.ai.account.BusinessIdentitySnapshot;
import com.example.ai.stream.ChatStream;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** 运行时按登录角色读取请求快照；固定身份构造器仅供隔离测试和教学实例使用。 */
@Component
public class DemoIdentity {
    public enum Role {
        BUYER("buyer"), CUSTOMER_SERVICE("service"), WAREHOUSE("warehouse");

        private final String code;

        Role(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        public static Role fromCode(String code) {
            for (Role role : values()) {
                if (role.code.equals(code)) {
                    return role;
                }
            }
            throw new IllegalArgumentException("角色仅支持 buyer、service、warehouse");
        }
    }
    public record Account(String id, Role role, String scope) { }
    public record Actor(String id, String username, String role) { }
    private final Account fixedAccount;
    private final AccountSessionAccess sessions;

    @Autowired
    public DemoIdentity(AccountSessionAccess sessions) {
        this.sessions = sessions;
        this.fixedAccount = null;
    }

    public DemoIdentity(String role) {
        this.fixedAccount = account(Role.fromCode(role));
        this.sessions = null;
    }

    public Account current() {
        return fixedAccount == null ? account(snapshot().role()) : fixedAccount;
    }

    public Actor actor() {
        if (fixedAccount != null) {
            return new Actor(fixedAccount.id(), fixedAccount.id(), fixedAccount.role().code());
        }
        var snapshot = snapshot();
        return new Actor(snapshot.session().accountId(), snapshot.username(), snapshot.role().code());
    }

    public String ownerId() { return current().id(); }

    public Runnable accessGuard(ChatStream stream) {
        if (fixedAccount != null) return () -> { };
        var captured = snapshot();
        return () -> {
            try {
                var active = sessions.requireActive(captured.session());
                if (active.passwordChangeRequired() || active.role() != captured.role()) {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号角色或密码状态已变化，请重新登录");
                }
            } catch (ResponseStatusException expired) {
                if (stream == null) throw expired;
                stream.cancel("账号状态已变化，生成已中断");
                throw new ChatStream.Interrupted("账号已失效或角色已变化，禁止继续执行");
            }
        };
    }

    public void requireActive(ChatStream stream) { accessGuard(stream).run(); }

    public <T> Supplier<T> bind(Supplier<T> task) {
        if (fixedAccount != null) return task;
        var captured = snapshot();
        return () -> {
            var previous = SecurityContextHolder.getContext();
            var context = SecurityContextHolder.createEmptyContext();
            var token = UsernamePasswordAuthenticationToken.authenticated(captured.session(), null, List.of());
            token.setDetails(captured);
            context.setAuthentication(token);
            SecurityContextHolder.setContext(context);
            try { return task.get(); }
            finally {
                SecurityContextHolder.clearContext();
                if (previous.getAuthentication() != null) SecurityContextHolder.setContext(previous);
            }
        };
    }

    private BusinessIdentitySnapshot snapshot() {
        var token = SecurityContextHolder.getContext().getAuthentication();
        if (token == null || !(token.getDetails() instanceof BusinessIdentitySnapshot snapshot)
                || !snapshot.session().equals(token.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少已认证的业务身份");
        }
        return snapshot;
    }

    private Account account(Role role) {
        return switch (role) {
            case BUYER -> new Account("demo-user", role, "买家角色共享订单 A1001、A1002、A1003");
            case CUSTOMER_SERVICE -> new Account("demo-service", role, "客服角色共享工单 AS2001 及关联订单 A1001");
            case WAREHOUSE -> new Account("demo-warehouse", role, "仓库角色共享 WH01 及订单 A1002");
        };
    }

    public boolean canAccessOrder(String number) {
        return switch (current().role()) {
            case BUYER -> java.util.Set.of("A1001", "A1002", "A1003").contains(number);
            case CUSTOMER_SERVICE -> "A1001".equals(number);
            case WAREHOUSE -> "A1002".equals(number);
        };
    }
}
