package com.medcollect;

import static com.medcollect.Support.*;

import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@DependsOnDatabaseInitialization
class UserAccounts implements UserDetailsService {
    private static final String INITIALIZATION_KEY = "user_accounts_initialized";
    private static final String VIEW_COLUMNS = "id, username, role_code, enabled, revision, created_at, updated_at";

    enum Role { ADMIN, REVIEWER }

    record Credentials(@NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 72) String password) {}
    record Create(@NotBlank @Size(max = 80) String username, @NotBlank @Size(max = 72) String password,
                  @NotNull Role role) {}
    record Update(@NotNull Role role, @NotNull Boolean enabled, @Positive int revision,
                  @Size(max = 72) String password) {}
    record Account(String id, String username, @JsonProperty("role") Role roleCode, boolean enabled,
                   int revision, long createdAt, long updatedAt) {}

    private record Login(String id, String username, String passwordHash, Role roleCode, boolean enabled, int revision) {}

    static final class AccountPrincipal extends User {
        private final String accountId;
        private final int revision;

        private AccountPrincipal(Login login) {
            super(login.username(), login.passwordHash(), login.enabled(), true, true, true,
                    AuthorityUtils.createAuthorityList("ROLE_" + login.roleCode().name()));
            this.accountId = login.id();
            this.revision = login.revision();
        }
    }

    private final Store store;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private final AppSettings settings;

    UserAccounts(Store store, PasswordEncoder passwords, TransactionTemplate transactions, AppSettings settings) {
        this.store = store;
        this.passwords = passwords;
        this.transactions = transactions;
        this.settings = settings;
    }

    @PostConstruct
    void bootstrap() {
        transactions.executeWithoutResult(tx -> {
            if ("true".equals(lockAccounts())) return;
            if (trim(settings.adminPassword()).isEmpty()) {
                throw new IllegalStateException("Configure MEDCOLLECT_ADMIN_PASSWORD to initialize user accounts");
            }
            String admin = validateUsername(settings.adminUser());
            insert(admin, passwords.encode(settings.adminPassword()), Role.ADMIN, "系统", "INITIALIZED");
            if (!trim(settings.reviewerUser()).isEmpty() || !trim(settings.reviewerPassword()).isEmpty()) {
                if (trim(settings.reviewerUser()).isEmpty() || trim(settings.reviewerPassword()).isEmpty()) {
                    throw new IllegalStateException("Configure both reviewer username and password");
                }
                insert(validateUsername(settings.reviewerUser()), passwords.encode(settings.reviewerPassword()),
                        Role.REVIEWER, "系统", "INITIALIZED");
            }
            store.db().sql("UPDATE mc_settings SET setting_value = 'true' WHERE setting_key = :key")
                    .param("key", INITIALIZATION_KEY).update();
        });
    }

    @Override
    public AccountPrincipal loadUserByUsername(String username) {
        Login login = store.db().sql("""
                SELECT id, username, password_hash, role_code, enabled, revision
                FROM mc_user_account WHERE username_key = :key
                """).param("key", usernameKey(username)).query(Login.class).optional()
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));
        return new AccountPrincipal(login);
    }

    boolean isCurrent(AccountPrincipal principal) {
        return store.db().sql("""
                SELECT count(*) FROM mc_user_account WHERE id = :id AND revision = :revision AND enabled = 1
                """).param("id", principal.accountId).param("revision", principal.revision)
                .query(Long.class).single() == 1;
    }

    Account register(Credentials input) {
        String username = validateUsername(input.username());
        return create(username, input.password(), Role.REVIEWER, username, "REGISTERED");
    }

    Account create(Create input, String actor) {
        return create(validateUsername(input.username()), input.password(), input.role(), actor, "CREATED");
    }

    private Account create(String username, String password, Role role, String actor, String action) {
        String hash = encodePassword(password);
        try {
            return transactions.execute(tx -> insert(username, hash, role, actor, action));
        } catch (DuplicateKeyException e) {
            throw conflict("该账号已存在");
        }
    }

    private Account insert(String username, String hash, Role role, String actor, String action) {
        String id = id();
        store.db().sql("""
                INSERT INTO mc_user_account (id, username, username_key, password_hash, role_code, enabled, revision,
                                             created_at, updated_at)
                VALUES (:id, :username, :key, :hash, :role, 1, 1, :now, :now)
                """).param("id", id).param("username", username).param("key", usernameKey(username))
                .param("hash", hash).param("role", role.name()).param("now", store.now()).update();
        store.event("USER", id, action, actor, role.name());
        return account(id);
    }

    Records.Page<Account> list(String query, int page, int size) {
        String key = usernameKey(query);
        String from = "FROM mc_user_account";
        if (!key.isEmpty()) from += " WHERE INSTR(username_key, :key) > 0";
        return store.page(VIEW_COLUMNS, from, "created_at DESC, id", key.isEmpty() ? Map.of() : Map.of("key", key),
                page, size, Account.class);
    }

    Account update(String id, Update input, String actor) {
        String hash = input.password() == null || input.password().isEmpty() ? null : encodePassword(input.password());
        return transactions.execute(tx -> {
            lockAccounts();
            Account current = account(id);
            if (current.revision() != input.revision()) throw conflict("账号已更新，请刷新后重试");
            if (current.roleCode() == input.role() && current.enabled() == input.enabled() && hash == null) return current;
            if (current.roleCode() == Role.ADMIN && current.enabled() && (input.role() != Role.ADMIN || !input.enabled())
                    && store.db().sql("SELECT count(*) FROM mc_user_account WHERE role_code = 'ADMIN' AND enabled = 1")
                    .query(Long.class).single() <= 1) {
                throw conflict("至少保留一个已启用的管理员");
            }
            changed(store.db().sql("""
                    UPDATE mc_user_account SET role_code = :role, enabled = :enabled, updated_at = :now,
                        password_hash = COALESCE(:hash, password_hash), revision = revision + 1
                    WHERE id = :id AND revision = :revision
                    """).param("id", id).param("role", input.role().name()).param("enabled", input.enabled() ? 1 : 0)
                    .param("now", store.now()).param("hash", hash, Types.VARCHAR).param("revision", input.revision()).update());
            store.event("USER", id, hash == null ? "UPDATED" : "PASSWORD_RESET", actor, input.role().name());
            return account(id);
        });
    }

    private Account account(String id) {
        return store.db().sql("SELECT " + VIEW_COLUMNS + " FROM mc_user_account WHERE id = :id")
                .param("id", id).query(Account.class).optional().orElseThrow(() -> missing("账号不存在"));
    }

    private String lockAccounts() {
        return store.db().sql("SELECT setting_value FROM mc_settings WHERE setting_key = :key FOR UPDATE")
                .param("key", INITIALIZATION_KEY).query(String.class).single();
    }

    private String encodePassword(String password) {
        if (password.isBlank() || password.codePointCount(0, password.length()) < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw bad("密码至少需要 8 个字符，UTF-8 编码后不超过 72 字节");
        }
        return passwords.encode(password);
    }

    private static String validateUsername(String value) {
        String username = trim(value);
        if (username.isEmpty() || username.length() > 80 || username.codePoints().anyMatch(Character::isISOControl)) {
            throw bad("请输入 1 至 80 个有效字符作为账号");
        }
        return username;
    }

    private static String usernameKey(String username) { return trim(username).toLowerCase(Locale.ROOT); }
}
