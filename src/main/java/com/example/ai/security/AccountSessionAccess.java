package com.example.ai.security;

import com.example.ai.account.AccountSession;
import com.example.ai.account.UserAccount;
import com.example.ai.repository.AccountRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** HTTP 认证与业务执行共同复用的账号有效性校验，不依赖 Servlet 或 Service。 */
@Component
public class AccountSessionAccess {
    private final AccountRepository accounts;

    public AccountSessionAccess(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public UserAccount requireActive(AccountSession session) {
        if (session == null) throw expired();
        return accounts.findById(session.accountId())
                .filter(account -> account.enabled() && account.sessionVersion() == session.version())
                .orElseThrow(this::expired);
    }

    private ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期，请重新登录");
    }
}
