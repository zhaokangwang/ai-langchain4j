package com.example.ai;

import com.example.ai.account.UserAccount;
import com.example.ai.repository.AccountRepository;
import com.example.ai.security.DemoIdentity.Role;
import com.example.ai.service.AccountService;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountFoundationTest {
    @TempDir Path directory;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private HikariDataSource open(String url, String version) {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername("sa");
        var migrations = Flyway.configure().dataSource(dataSource);
        if (version != null) {
            migrations.target(MigrationVersion.fromVersion(version));
        }
        migrations.load().migrate();
        return dataSource;
    }

    private HikariDataSource memory() {
        return open("jdbc:h2:mem:accounts-" + UUID.randomUUID(), null);
    }

    private AccountService service(HikariDataSource dataSource) {
        return new AccountService(new AccountRepository(new JdbcTemplate(dataSource)), encoder);
    }

    @Test
    void shouldStoreOnlySaltedHashAndReuseTheThreeBusinessRoles() {
        try (var dataSource = memory()) {
            var accounts = service(dataSource);
            var administrator = accounts.initializeAdministrator("admin");
            assertThat(administrator.username()).isEqualTo("admin");
            assertThat(administrator.role()).isEqualTo(Role.BUYER);
            assertThat(administrator.administrator()).isTrue();
            assertThat(administrator.enabled()).isTrue();
            assertThat(administrator.passwordChangeRequired()).isTrue();
            assertThat(administrator.sessionVersion()).isZero();
            assertThat(administrator.passwordHash()).startsWith("$2a$").isNotEqualTo("admin");
            assertThat(encoder.matches("admin", administrator.passwordHash())).isTrue();
            assertThat(encoder.matches("wrong-password", administrator.passwordHash())).isFalse();
            assertThat(encoder.encode("admin")).isNotEqualTo(administrator.passwordHash());
            assertThat(administrator.toString()).doesNotContain(administrator.passwordHash());
            assertThat(accounts.findByUsername(" ADMIN ")).contains(administrator);
            assertThat(accounts.findById(administrator.id())).contains(administrator);
            assertThat(accounts.findByUsername("admin' OR '1'='1")).isEmpty();
            assertThat(accounts.findByUsername(null)).isEmpty();
            assertThat(Role.values()).extracting(Role::code).containsExactly("buyer", "service", "warehouse");
            for (Role role : Role.values()) {
                assertThat(Role.fromCode(role.code())).isEqualTo(role);
            }
            assertThatThrownBy(() -> Role.fromCode("admin")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shouldPersistEveryBusinessRoleWithoutGrantingAdministration() {
        try (var dataSource = memory()) {
            var repository = new AccountRepository(new JdbcTemplate(dataSource));
            String hash = encoder.encode("test-password");
            for (Role role : Role.values()) {
                var account = new UserAccount(UUID.randomUUID().toString(), role.code(), hash, role,
                        false, true, false, 0, 1, 1);
                repository.insert(account);
                assertThat(repository.findById(account.id())).contains(account);
                assertThat(repository.findByUsername(role.code())).contains(account);
            }
        }
    }

    @Test
    void shouldPreservePasswordRoleStatusAndIdentityAfterFileDatabaseRestart() {
        String url = "jdbc:h2:file:" + directory.resolve("accounts").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE";
        UserAccount changed;
        try (var dataSource = open(url, null)) {
            var accounts = service(dataSource);
            var original = accounts.initializeAdministrator("first-password");
            assertThat(accounts.initializeAdministrator("another-password")).isEqualTo(original);
            String changedHash = encoder.encode("changed-password");
            new JdbcTemplate(dataSource).update("""
                    UPDATE user_accounts SET password_hash=?,role='warehouse',enabled=FALSE,
                    password_change_required=FALSE,session_version=7,updated_at=123 WHERE id=?
                    """, changedHash, original.id());
            changed = accounts.findById(original.id()).orElseThrow();
        }
        try (var dataSource = open(url, null)) {
            // 已存在时初始化密码配置不再参与校验，也不能覆盖已有账号。
            assertThat(service(dataSource).initializeAdministrator("")).isEqualTo(changed);
            assertThat(encoder.matches("changed-password", changed.passwordHash())).isTrue();
            assertThat(new JdbcTemplate(dataSource).queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void shouldUpgradeVersionFiveWithoutDeletingHistoryOrReportData() {
        String url = "jdbc:h2:file:" + directory.resolve("upgrade").toString().replace('\\', '/')
                + ";DB_CLOSE_ON_EXIT=FALSE";
        try (var dataSource = open(url, "5")) {
            new JdbcTemplate(dataSource).update("""
                    INSERT INTO conversations(id,owner_id,title,source,created_at,updated_at)
                    VALUES('old-conversation','demo-user','已有会话','CHAT',1,1)
                    """);
        }
        try (var dataSource = open(url, null)) {
            service(dataSource).initializeAdministrator("admin");
            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT title FROM conversations WHERE id='old-conversation'", String.class))
                    .isEqualTo("已有会话");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM report_orders", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void shouldRejectInvalidInitialPasswordsAndAcceptUtf8ByteLimit() {
        try (var dataSource = memory()) {
            var accounts = service(dataSource);
            assertThatThrownBy(() -> accounts.initializeAdministrator(null)).isInstanceOf(IllegalArgumentException.class);
            for (String password : List.of("", "    ", "abc", "x".repeat(73), "密".repeat(25))) {
                assertThatThrownBy(() -> accounts.initializeAdministrator(password))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(accounts.findByUsername("admin")).isEmpty();
            var administrator = accounts.initializeAdministrator("密".repeat(24));
            assertThat(encoder.matches("密".repeat(24), administrator.passwordHash())).isTrue();
        }
    }

    @Test
    void shouldEnforceUniqueCanonicalUsernameAndKnownRoleInDatabase() {
        try (var dataSource = memory()) {
            var accounts = service(dataSource);
            var administrator = accounts.initializeAdministrator("admin");
            var repository = new AccountRepository(new JdbcTemplate(dataSource));
            var duplicate = new UserAccount(UUID.randomUUID().toString(), "admin", administrator.passwordHash(),
                    Role.BUYER, false, true, false, 0, 1, 1);
            assertThatThrownBy(() -> repository.insert(duplicate)).isInstanceOf(DuplicateKeyException.class);
            var jdbc = new JdbcTemplate(dataSource);
            assertThatThrownBy(() -> jdbc.update("UPDATE user_accounts SET role='admin' WHERE id=?", administrator.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE user_accounts SET username=' ADMIN ' WHERE id=?", administrator.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(accounts.findById(administrator.id())).contains(administrator);
        }
    }

    @Test
    void shouldNotPromoteExistingOrdinaryAccountNamedAdmin() {
        try (var dataSource = memory()) {
            var ordinary = new UserAccount(UUID.randomUUID().toString(), "admin", encoder.encode("ordinary-password"),
                    Role.BUYER, false, true, false, 0, 1, 1);
            var repository = new AccountRepository(new JdbcTemplate(dataSource));
            repository.insert(ordinary);
            assertThatThrownBy(() -> service(dataSource).initializeAdministrator("admin"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("没有管理员标记");
            assertThat(repository.findByUsername("admin")).contains(ordinary);
        }
    }

    @Test
    void shouldReuseAdministratorInsertedByCompetingInitializer() {
        var repository = mock(AccountRepository.class);
        var existing = new UserAccount("existing-id", "admin", encoder.encode("first-password"), Role.BUYER,
                true, true, true, 0, 1, 1);
        when(repository.findByUsername("admin")).thenReturn(Optional.empty()).thenReturn(Optional.of(existing));
        doThrow(new DuplicateKeyException("competing insert")).when(repository).insert(any(UserAccount.class));
        assertThat(new AccountService(repository, encoder).initializeAdministrator("another-password")).isEqualTo(existing);
    }
}
