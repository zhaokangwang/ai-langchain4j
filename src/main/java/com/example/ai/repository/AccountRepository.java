package com.example.ai.repository;

import com.example.ai.account.UserAccount;
import com.example.ai.account.AccountManagementModels.AccountSummary;
import com.example.ai.security.DemoIdentity.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {
    private static final String COLUMNS = """
            id,username,password_hash,role,administrator,enabled,password_change_required,
            session_version,created_at,updated_at
            """;
    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserAccount> findByUsername(String username) {
        return jdbc.query("SELECT " + COLUMNS + " FROM user_accounts WHERE username=?",
                (rs, n) -> account(rs), username).stream().findFirst();
    }

    public Optional<UserAccount> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM user_accounts WHERE id=?",
                (rs, n) -> account(rs), id).stream().findFirst();
    }

    public Optional<UserAccount> lockById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM user_accounts WHERE id=? FOR UPDATE",
                (rs, n) -> account(rs), id).stream().findFirst();
    }

    public List<AccountSummary> search(String keyword, String role, Boolean enabled, int limit, int offset) {
        var sql = new StringBuilder("""
                SELECT id,username,role,administrator,enabled,password_change_required,created_at,updated_at
                FROM user_accounts WHERE 1=1
                """);
        var parameters = new ArrayList<Object>();
        if (!keyword.isEmpty()) {
            sql.append(" AND username LIKE ? ESCAPE '!'");
            parameters.add("%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
        }
        if (!role.isEmpty()) {
            sql.append(" AND role=?");
            parameters.add(role);
        }
        if (enabled != null) {
            sql.append(" AND enabled=?");
            parameters.add(enabled);
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?");
        parameters.add(limit);
        parameters.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> new AccountSummary(rs.getString("id"), rs.getString("username"),
                rs.getString("role"), rs.getBoolean("administrator"), rs.getBoolean("enabled"),
                rs.getBoolean("password_change_required"), rs.getLong("created_at"), rs.getLong("updated_at")), parameters.toArray());
    }

    public void resetPassword(String id, String hash) {
        jdbc.update("""
                UPDATE user_accounts SET password_hash=?,password_change_required=TRUE,
                session_version=session_version+1,updated_at=? WHERE id=?
                """, hash, System.currentTimeMillis(), id);
    }

    public void changeEnabled(String id, boolean enabled) {
        jdbc.update("""
                UPDATE user_accounts SET enabled=?,session_version=session_version+1,updated_at=? WHERE id=?
                """, enabled, System.currentTimeMillis(), id);
    }

    public void insert(UserAccount account) {
        jdbc.update("INSERT INTO user_accounts(" + COLUMNS + ") VALUES(?,?,?,?,?,?,?,?,?,?)",
                account.id(), account.username(), account.passwordHash(), account.role().code(),
                account.administrator(), account.enabled(), account.passwordChangeRequired(),
                account.sessionVersion(), account.createdAt(), account.updatedAt());
    }

    public boolean changePassword(String id, long version, String previousHash, String newHash) {
        return jdbc.update("""
                UPDATE user_accounts SET password_hash=?,password_change_required=FALSE,
                session_version=session_version+1,updated_at=?
                WHERE id=? AND session_version=? AND password_hash=? AND enabled=TRUE
                """, newHash, System.currentTimeMillis(), id, version, previousHash) == 1;
    }

    private UserAccount account(ResultSet rs) throws SQLException {
        return new UserAccount(rs.getString("id"), rs.getString("username"), rs.getString("password_hash"),
                Role.fromCode(rs.getString("role")), rs.getBoolean("administrator"), rs.getBoolean("enabled"),
                rs.getBoolean("password_change_required"), rs.getLong("session_version"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
