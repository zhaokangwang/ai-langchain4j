package com.example.ai;

import com.example.ai.account.AccountSession;
import com.example.ai.account.UserAccount;
import com.example.ai.repository.AccountRepository;
import com.example.ai.security.DemoIdentity.Role;
import java.util.List;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 原有业务测试使用真实存在的测试账号和有效 CSRF；认证边界由独立测试验证。 */
@TestConfiguration(proxyBeanMethods = false)
public class TestAuthenticatedRequests {
    @Bean
    @DependsOnDatabaseInitialization
    public MockMvcBuilderCustomizer authenticatedBusinessRequests(AccountRepository accounts, PasswordEncoder passwords) {
        var account = ensureAccount(accounts, passwords);
        var principal = new AccountSession(account.id(), account.sessionVersion());
        var authenticated = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        // MockMvc 的过滤器会清除线程身份；恢复显式测试快照供同一测试后续的 Service 调用。
        return builder -> builder.defaultRequest(get("/").with(authentication(authenticated)).with(csrf()))
                .alwaysDo(result -> SecurityContextHolder.setContext(TestBusinessIdentityFactory.context(account)));
    }

    public static UserAccount ensureAccount(AccountRepository accounts, PasswordEncoder passwords) {
        return accounts.findByUsername("business-test").orElseGet(() -> {
            var account = new UserAccount("business-test-account", "business-test", passwords.encode("test-password"),
                    Role.BUYER, false, true, false, 0, 1, 1);
            accounts.insert(account);
            return account;
        });
    }
}
