package com.example.ai;

import com.example.ai.account.AccountManagementModels.CreateAccountRequest;
import com.example.ai.account.AccountSession;
import com.example.ai.account.UserAccount;
import com.example.ai.repository.AccountRepository;
import com.example.ai.security.DemoIdentity.Role;
import com.example.ai.service.AccountAdministrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AccountAdministrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository repository;
    @Autowired AccountAdministrationService accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ChatModel model;

    private String name() { return "user-" + UUID.randomUUID(); }

    private UserAccount fixture(boolean administrator) {
        return fixture(name(), Role.BUYER, administrator, true);
    }

    private UserAccount fixture(String username, Role role, boolean administrator, boolean enabled) {
        var account = new UserAccount(UUID.randomUUID().toString(), username, passwords.encode("test-password"), role,
                administrator, enabled, false, 0, 1, 1);
        repository.insert(account);
        return account;
    }

    private MockHttpSession login(UserAccount account) throws Exception {
        return login(account.username(), "test-password");
    }

    private MockHttpSession login(String username, String password) throws Exception {
        var result = mvc.perform(json(post("/api/auth/login"), null, Map.of("username", username, "password", password)))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, MockHttpSession session, Object body) throws Exception {
        if (session != null) request.session(session);
        return request.with(csrf()).contentType("application/json").content(mapper.writeValueAsString(body));
    }

    private AccountSession actor(UserAccount account) { return new AccountSession(account.id(), account.sessionVersion()); }

    @Test
    void shouldRejectAnonymousAndOrdinaryAccountsAtHttpAndServiceBoundaries() throws Exception {
        mvc.perform(get("/api/admin/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin-accounts.html")).andExpect(status().is3xxRedirection());
        var user = fixture(false);
        var session = login(user);
        mvc.perform(get("/api/admin/accounts").session(session).header("X-Administrator", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/admin-accounts.html").session(session)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/admin/accounts"), session, Map.of("username", name(), "password", "test-password", "role", "buyer")))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/admin/accounts/{id}/password", user.id()), session, Map.of("newPassword", "new-password")))
                .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/admin/accounts/{id}/status", user.id()), session, Map.of("enabled", false)))
                .andExpect(status().isForbidden());
        assertThatThrownBy(() -> accounts.list(actor(user), "", "", null, 0, 20))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(model);
    }

    @Test
    void shouldCreateOrdinaryAccountsForAllThreeRolesWithoutAcceptingPrivilegeFields() throws Exception {
        var session = login(fixture(true));
        for (String role : List.of("buyer", "service", "warehouse")) {
            String username = name();
            var result = mvc.perform(json(post("/api/admin/accounts"), session,
                            Map.of("username", username.toUpperCase(java.util.Locale.ROOT), "password", "initial-password",
                                    "role", role, "administrator", true, "enabled", false)))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value(username))
                    .andExpect(jsonPath("$.role").value(role)).andExpect(jsonPath("$.administrator").value(false))
                    .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.passwordChangeRequired").value(true))
                    .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.sessionVersion").doesNotExist()).andReturn();
            var stored = repository.findByUsername(username).orElseThrow();
            assertThat(passwords.matches("initial-password", stored.passwordHash())).isTrue();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(stored.passwordHash(), "initial-password");
            var createdSession = login(username, "initial-password");
            mvc.perform(get("/api/auth/me").session(createdSession)).andExpect(jsonPath("$.role").value(role));
            mvc.perform(get("/api/demo/capabilities").session(createdSession)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.passwordChangeRequired").value(true));
        }
        verifyNoInteractions(model);
    }

    @Test
    void shouldRejectDuplicateNamesAndInvalidCreateFields() throws Exception {
        var session = login(fixture(true));
        String username = name();
        var body = Map.of("username", username, "password", "initial-password", "role", "buyer");
        mvc.perform(json(post("/api/admin/accounts"), session, body)).andExpect(status().isCreated());
        mvc.perform(json(post("/api/admin/accounts"), session, body)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("账号已存在"));
        for (var invalid : List.of(Map.of("username", name(), "password", "test-password", "role", "admin"),
                Map.of("username", "bad name", "password", "test-password", "role", "buyer"),
                Map.of("username", name(), "password", "密".repeat(25), "role", "buyer"))) {
            mvc.perform(json(post("/api/admin/accounts"), session, invalid)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void shouldPageStablyAndFilterRoleStatusAndLiteralKeywordWithoutExposingSecrets() throws Exception {
        var session = login(fixture(true));
        String marker = "find-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 0; i < 5; i++) fixture(marker + "_" + i, Role.values()[i % 3], false, i % 2 == 0);
        fixture(marker + "xa", Role.BUYER, false, true);
        var ids = new ArrayList<String>();
        for (int page = 0; page < 3; page++) {
            var result = mvc.perform(get("/api/admin/accounts").session(session).param("keyword", marker.toUpperCase(java.util.Locale.ROOT) + "_")
                            .param("page", String.valueOf(page)).param("size", "2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(page)).andExpect(jsonPath("$.size").value(2))
                    .andExpect(jsonPath("$.hasMore").value(page < 2))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn();
            var rows = mapper.readTree(result.getResponse().getContentAsString()).path("items");
            for (var row : rows) { ids.add(row.path("id").asText()); assertThat(row.has("passwordHash")).isFalse(); assertThat(row.has("sessionVersion")).isFalse(); }
        }
        assertThat(ids).hasSize(5);
        assertThat(new HashSet<>(ids)).hasSize(5);
        mvc.perform(get("/api/admin/accounts").session(session).param("keyword", marker).param("role", "service").param("enabled", "false"))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].username").value(marker + "_1"));
        for (String keyword : List.of(marker + "%", "' OR '1'='1")) {
            mvc.perform(get("/api/admin/accounts").session(session).param("keyword", keyword)).andExpect(jsonPath("$.items.length()").value(0));
        }
    }

    @Test
    void shouldResetPasswordInvalidateOldSessionsAndRequireChangeOnNextLogin() throws Exception {
        var admin = login(fixture(true));
        var user = fixture(false);
        var oldSession = login(user);
        mvc.perform(json(post("/api/admin/accounts/{id}/password", user.id()), admin, Map.of("newPassword", "reset-password")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(oldSession)).andExpect(status().isUnauthorized());
        var stored = repository.findById(user.id()).orElseThrow();
        assertThat(stored.sessionVersion()).isEqualTo(1);
        assertThat(stored.passwordChangeRequired()).isTrue();
        assertThat(stored.enabled()).isTrue();
        mvc.perform(json(post("/api/auth/login"), null, Map.of("username", user.username(), "password", "test-password")))
                .andExpect(status().isUnauthorized());
        var next = login(user.username(), "reset-password");
        mvc.perform(get("/api/auth/me").session(next)).andExpect(jsonPath("$.passwordChangeRequired").value(true));
        mvc.perform(get("/api/admin/accounts").session(next)).andExpect(status().isForbidden());
    }

    @Test
    void shouldDisableEnableAndNeverReviveOldSessionsEvenWhenNotUsedWhileDisabled() throws Exception {
        var admin = login(fixture(true));
        var user = fixture(false);
        var oldSession = login(user);
        mvc.perform(json(patch("/api/admin/accounts/{id}/status", user.id()), admin, Map.of("enabled", false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(json(post("/api/auth/login"), null, Map.of("username", user.username(), "password", "test-password")))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/admin/accounts/{id}/password", user.id()), admin, Map.of("newPassword", "reset-password")))
                .andExpect(status().isNoContent());
        assertThat(repository.findById(user.id()).orElseThrow().enabled()).isFalse();
        mvc.perform(json(patch("/api/admin/accounts/{id}/status", user.id()), admin, Map.of("enabled", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        mvc.perform(get("/api/auth/me").session(oldSession)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(login(user.username(), "reset-password"))).andExpect(status().isOk());
    }

    @Test
    void shouldProtectPresetAdministratorAndSelfAndKeepRepeatedStatusChangesIdempotent() throws Exception {
        var manager = fixture(true);
        var session = login(manager);
        for (String id : List.of(repository.findByUsername("admin").orElseThrow().id(), manager.id())) {
            mvc.perform(json(patch("/api/admin/accounts/{id}/status", id), session, Map.of("enabled", false)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isString());
        }
        var user = fixture(false);
        mvc.perform(json(patch("/api/admin/accounts/{id}/status", user.id()), session, Map.of("enabled", true)))
                .andExpect(status().isOk());
        assertThat(repository.findById(user.id())).contains(user);
        mvc.perform(json(patch("/api/admin/accounts/{id}/status", user.id()), session, Map.of("enabled", false))).andExpect(status().isOk());
        var disabled = repository.findById(user.id()).orElseThrow();
        mvc.perform(json(patch("/api/admin/accounts/{id}/status", user.id()), session, Map.of("enabled", false))).andExpect(status().isOk());
        assertThat(repository.findById(user.id())).contains(disabled);
    }

    @Test
    void shouldValidateQueryPathAndMissingStatusAndReturnNotFoundForUnknownAccount() throws Exception {
        var session = login(fixture(true));
        for (var entry : Map.of("page", "-1", "size", "101", "role", "admin", "enabled", "maybe", "keyword", "x".repeat(65)).entrySet()) {
            mvc.perform(get("/api/admin/accounts").session(session).param(entry.getKey(), entry.getValue())).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/admin/accounts").session(session).param("page", "100001")).andExpect(status().isBadRequest());
        mvc.perform(json(patch("/api/admin/accounts/{id}/status", UUID.randomUUID()), session, Map.of()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/admin/accounts/not-an-id/password"), session, Map.of("newPassword", "test-password")))
                .andExpect(status().isBadRequest());
        mvc.perform(json(patch("/api/admin/accounts/{id}/status", UUID.randomUUID()), session, Map.of("enabled", false)))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/admin/accounts/{id}/password", UUID.randomUUID()), session, Map.of("newPassword", "test-password")))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRecheckAdministrationAndPasswordChangeStateInsteadOfTrustingSessionClaims() throws Exception {
        var manager = fixture(true);
        var session = login(manager);
        jdbc.update("UPDATE user_accounts SET administrator=FALSE WHERE id=?", manager.id());
        mvc.perform(get("/api/admin/accounts").session(session)).andExpect(status().isForbidden());
        assertThatThrownBy(() -> accounts.create(actor(manager), new CreateAccountRequest(name(), "test-password", "buyer")))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        jdbc.update("UPDATE user_accounts SET administrator=TRUE,password_change_required=TRUE WHERE id=?", manager.id());
        assertThatThrownBy(() -> accounts.list(actor(manager), "", "", null, 0, 20))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
    }

    @Test
    void shouldProtectAllManagementWritesWithCsrfAndServeAdminPageWithoutCallingModel() throws Exception {
        var session = login(fixture(true));
        var user = fixture(false);
        mvc.perform(post("/api/admin/accounts").session(session).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/accounts/{id}/password", user.id()).session(session)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/admin/accounts/{id}/status", user.id()).session(session)).andExpect(status().isForbidden());
        mvc.perform(get("/admin-accounts.html").session(session)).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("账号列表", "添加账号"));
        mvc.perform(get("/js/admin-accounts.js")).andExpect(status().isOk());
        assertThat(repository.findById(user.id())).contains(user);
        verifyNoInteractions(model);
    }

    @Test
    void shouldRejectInvalidPasswordsWithoutLoggingOrEchoingRejectedValues(CapturedOutput output) throws Exception {
        var session = login(fixture(true));
        var user = fixture(false);
        String secret = "sensitive-management-password-" + "x".repeat(80);
        for (var request : List.of(json(post("/api/admin/accounts"), session,
                Map.of("username", name(), "password", secret, "role", "buyer")),
                json(post("/api/admin/accounts/{id}/password", user.id()), session, Map.of("newPassword", secret)))) {
            mvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain(secret, "rejectedValue"));
        }
        assertThat(output.getAll()).doesNotContain(secret, "rejected value");
        assertThat(repository.findById(user.id())).contains(user);
    }

    @Test
    void shouldValidateServiceInputsAndInvalidateOwnSessionAfterAdministrativeReset() throws Exception {
        var manager = fixture(true);
        var actor = actor(manager);
        for (var request : List.of(new CreateAccountRequest("bad name", "test-password", "buyer"),
                new CreateAccountRequest(name(), "test-password", "admin"), new CreateAccountRequest(name(), "abc", "buyer"))) {
            assertThatThrownBy(() -> accounts.create(actor, request)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        }
        assertThatThrownBy(() -> accounts.changeStatus(actor, manager.id(), null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        var session = login(manager);
        mvc.perform(json(post("/api/admin/accounts/{id}/password", manager.id()), session, Map.of("newPassword", "own-reset-password")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(login(manager.username(), "own-reset-password")))
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
    }
}
