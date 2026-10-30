package com.example.ai.service;

import com.example.ai.account.AccountSession;
import com.example.ai.account.AuthenticationModels.CurrentAccount;
import com.example.ai.account.AuthenticationModels.LoginResult;
import com.example.ai.account.UserAccount;
import com.example.ai.account.AccountPasswordPolicy;
import com.example.ai.repository.AccountRepository;
import com.example.ai.security.AccountSessionAccess;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthenticationService {
    private final AccountService accounts;
    private final AccountRepository repository;
    private final PasswordEncoder passwords;
    private final AccountSessionAccess sessions;
    private final String dummyHash;

    public AuthenticationService(AccountService accounts, AccountRepository repository, PasswordEncoder passwords, AccountSessionAccess sessions) {
        this.accounts = accounts;
        this.repository = repository;
        this.passwords = passwords;
        this.sessions = sessions;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    public LoginResult login(String username, String password) {
        var account = accounts.findByUsername(username);
        String hash = account.map(UserAccount::passwordHash).orElse(dummyHash);
        boolean matches = AccountPasswordPolicy.isValid(password) && passwords.matches(password, hash);
        if (!matches || account.isEmpty() || !account.orElseThrow().enabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码错误，或账号已禁用");
        }
        var authenticated = account.orElseThrow();
        return new LoginResult(new AccountSession(authenticated.id(), authenticated.sessionVersion()), view(authenticated));
    }

    public CurrentAccount current(AccountSession session) {
        return view(sessions.requireActive(session));
    }

    public void changePassword(AccountSession session, String currentPassword, String newPassword) {
        var account = sessions.requireActive(session);
        if (!AccountPasswordPolicy.isValid(currentPassword) || !passwords.matches(currentPassword, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        if (!AccountPasswordPolicy.isValid(newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少 4 个字符，UTF-8 编码不超过 72 字节");
        }
        if (passwords.matches(newPassword, account.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码不能与当前密码相同");
        }
        String hash = passwords.encode(newPassword);
        if (!repository.changePassword(account.id(), session.version(), account.passwordHash(), hash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号状态已变化，请重新登录后再试");
        }
    }

    private CurrentAccount view(UserAccount account) {
        return new CurrentAccount(account.id(), account.username(), account.role().code(),
                account.administrator(), account.passwordChangeRequired());
    }
}
