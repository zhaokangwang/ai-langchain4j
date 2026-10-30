package com.example.ai.service;

import com.example.ai.account.AccountManagementModels.AccountPage;
import com.example.ai.account.AccountManagementModels.AccountSummary;
import com.example.ai.account.AccountManagementModels.CreateAccountRequest;
import com.example.ai.account.AccountPasswordPolicy;
import com.example.ai.account.AccountSession;
import com.example.ai.account.UserAccount;
import com.example.ai.repository.AccountRepository;
import com.example.ai.security.DemoIdentity.Role;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountAdministrationService {
    private final AccountRepository repository;
    private final AuthenticationService authentication;
    private final PasswordEncoder passwords;
    private final TransactionTemplate tx;

    public AccountAdministrationService(AccountRepository repository, AuthenticationService authentication,
                                        PasswordEncoder passwords, PlatformTransactionManager manager) {
        this.repository = repository;
        this.authentication = authentication;
        this.passwords = passwords;
        this.tx = new TransactionTemplate(manager);
    }

    public AccountPage list(AccountSession actor, String keyword, String role, Boolean enabled, int page, int size) {
        requireAdministrator(actor);
        String search = keyword == null ? "" : keyword.strip().toLowerCase(Locale.ROOT);
        String selectedRole = role == null ? "" : role;
        if (search.length() > 64 || page < 0 || page > 100_000 || size < 1 || size > 100) {
            throw bad("查询或分页参数无效");
        }
        if (!selectedRole.isEmpty()) {
            parseRole(selectedRole);
        }
        var rows = repository.search(search, selectedRole, enabled, size + 1, page * size);
        return new AccountPage(List.copyOf(rows.subList(0, Math.min(size, rows.size()))), page, size, rows.size() > size);
    }

    public AccountSummary create(AccountSession actor, CreateAccountRequest request) {
        requireAdministrator(actor);
        if (request == null || request.username() == null) {
            throw bad("请填写账号");
        }
        String username = request.username().strip().toLowerCase(Locale.ROOT);
        if (!username.matches("[a-z0-9][a-z0-9._-]{2,63}")) {
            throw bad("账号须为 3–64 位字母、数字、点、下划线或短横线，且以字母或数字开头");
        }
        Role role = parseRole(request.role());
        validatePassword(request.password());
        String hash = passwords.encode(request.password());
        long now = System.currentTimeMillis();
        var account = new UserAccount(UUID.randomUUID().toString(), username, hash, role, false, true, true, 0, now, now);
        try {
            return tx.execute(ignored -> {
                requireAdministrator(actor);
                repository.insert(account);
                return summary(account);
            });
        } catch (DuplicateKeyException duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "账号已存在");
        }
    }

    public void resetPassword(AccountSession actor, String id, String newPassword) {
        requireAdministrator(actor);
        validateId(id);
        validatePassword(newPassword);
        String hash = passwords.encode(newPassword);
        tx.executeWithoutResult(ignored -> {
            requireAdministrator(actor);
            requireTarget(id);
            repository.resetPassword(id, hash);
        });
    }

    public AccountSummary changeStatus(AccountSession actor, String id, Boolean enabled) {
        requireAdministrator(actor);
        validateId(id);
        if (enabled == null) {
            throw bad("请指定启用或禁用状态");
        }
        return tx.execute(ignored -> {
            requireAdministrator(actor);
            var target = requireTarget(id);
            if (!enabled && (target.username().equals("admin") || target.id().equals(actor.accountId()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "不能禁用预设管理员或当前操作账号");
            }
            if (target.enabled() != enabled) {
                repository.changeEnabled(id, enabled);
            }
            return summary(repository.findById(id).orElseThrow());
        });
    }

    private void requireAdministrator(AccountSession actor) {
        var account = authentication.current(actor);
        if (!account.administrator() || account.passwordChangeRequired()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅已完成改密的管理员可维护账号");
        }
    }

    private UserAccount requireTarget(String id) {
        return repository.lockById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
    }

    private Role parseRole(String role) {
        try {
            return Role.fromCode(role);
        } catch (IllegalArgumentException invalid) {
            throw bad("角色仅支持 buyer、service、warehouse");
        }
    }

    private void validatePassword(String password) {
        if (!AccountPasswordPolicy.isValid(password)) {
            throw bad("密码至少 4 个字符，UTF-8 编码不超过 72 字节");
        }
    }

    private void validateId(String id) {
        if (id == null || !id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw bad("账号编号格式无效");
        }
    }

    private AccountSummary summary(UserAccount account) {
        return new AccountSummary(account.id(), account.username(), account.role().code(), account.administrator(),
                account.enabled(), account.passwordChangeRequired(), account.createdAt(), account.updatedAt());
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
