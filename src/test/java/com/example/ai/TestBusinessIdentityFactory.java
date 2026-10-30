package com.example.ai;

import com.example.ai.account.AccountSession;
import com.example.ai.account.BusinessIdentitySnapshot;
import com.example.ai.account.UserAccount;
import com.example.ai.repository.AccountRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

public class TestBusinessIdentityFactory implements WithSecurityContextFactory<WithBusinessIdentity> {
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwords;

    @Override
    public SecurityContext createSecurityContext(WithBusinessIdentity annotation) {
        var account = TestAuthenticatedRequests.ensureAccount(accounts, passwords);
        return context(account);
    }

    public static SecurityContext context(UserAccount account) {
        var principal = new AccountSession(account.id(), account.sessionVersion());
        var token = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        token.setDetails(new BusinessIdentitySnapshot(principal, account.username(), account.role()));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        return context;
    }
}
