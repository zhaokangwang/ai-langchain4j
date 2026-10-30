package com.example.ai;

import com.example.ai.account.AccountSession;
import com.example.ai.account.BusinessIdentitySnapshot;
import com.example.ai.account.UserAccount;
import com.example.ai.repository.AccountRepository;
import com.example.ai.security.DemoIdentity.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 角色测试使用隔离账号和实际登录接口；固定上下文只用于显式的异步边界测试。 */
final class RoleTestSupport {
    private final AccountRepository accounts;
    private final PasswordEncoder passwords;
    private final MockMvc mvc;
    private final ObjectMapper mapper;

    RoleTestSupport(AccountRepository accounts, PasswordEncoder passwords, MockMvc mvc, ObjectMapper mapper) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.mvc = mvc;
        this.mapper = mapper;
    }

    UserAccount account(Role role) { return account(role, false); }

    UserAccount account(Role role, boolean administrator) {
        String id = UUID.randomUUID().toString();
        var account = new UserAccount(id, "role-" + id, passwords.encode("test-password"), role,
                administrator, true, false, 0, 1, 1);
        accounts.insert(account);
        return account;
    }

    MockHttpSession login(UserAccount account) throws Exception {
        var result = mvc.perform(json(post("/api/auth/login"), null,
                        Map.of("username", account.username(), "password", "test-password")))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, MockHttpSession session, Object body) throws Exception {
        if (session != null) request.session(session);
        return request.with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body));
    }

    static <T> T as(UserAccount account, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var session = new AccountSession(account.id(), account.sessionVersion());
        var token = UsernamePasswordAuthenticationToken.authenticated(session, null, List.of());
        token.setDetails(new BusinessIdentitySnapshot(session, account.username(), account.role()));
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
        try { return action.get(); }
        finally { SecurityContextHolder.setContext(previous); }
    }
}
