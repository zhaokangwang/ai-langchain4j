package com.example.ai.service;

import com.example.ai.account.UserAccount;
import com.example.ai.account.AccountPasswordPolicy;
import com.example.ai.repository.AccountRepository;
import com.example.ai.security.DemoIdentity.Role;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AccountService {
    private final AccountRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AccountService(AccountRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public Optional<UserAccount> findByUsername(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return repository.findByUsername(username.strip().toLowerCase(Locale.ROOT));
    }

    public Optional<UserAccount> findById(String id) {
        return repository.findById(id);
    }

    /** 仅供应用启动初始化，不作为账号管理或密码重置入口。 */
    public UserAccount initializeAdministrator(String initialPassword) {
        var existing = repository.findByUsername("admin");
        if (existing.isPresent()) {
            return requireAdministrator(existing.orElseThrow());
        }
        validateInitialPassword(initialPassword);
        String hash = passwordEncoder.encode(initialPassword);
        long now = System.currentTimeMillis();
        var administrator = new UserAccount(UUID.randomUUID().toString(), "admin", hash, Role.BUYER,
                true, true, true, 0, now, now);
        try {
            repository.insert(administrator);
            return administrator;
        } catch (DuplicateKeyException conflict) {
            // 并发初始化时只接受另一初始化已创建的管理员，不覆盖任何已有字段。
            return repository.findByUsername("admin").map(this::requireAdministrator).orElseThrow(() -> conflict);
        }
    }

    private UserAccount requireAdministrator(UserAccount account) {
        if (!account.administrator()) {
            throw new IllegalStateException("预设账号 admin 已存在但没有管理员标记，请检查账号初始化数据");
        }
        return account;
    }

    private void validateInitialPassword(String password) {
        if (!AccountPasswordPolicy.isValid(password)) {
            throw new IllegalArgumentException("管理员初始密码至少 4 个字符，UTF-8 编码不超过 72 字节");
        }
    }
}
