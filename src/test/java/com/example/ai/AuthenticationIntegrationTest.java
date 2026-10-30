package com.example.ai;

import com.example.ai.account.AccountSession;
import com.example.ai.account.UserAccount;
import com.example.ai.repository.AccountRepository;
import com.example.ai.security.DemoIdentity.Role;
import com.example.ai.service.AuthenticationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthenticationService authentication;
    @MockitoBean ChatModel model;

    private UserAccount account(Role role, boolean changeRequired) {
        String id = UUID.randomUUID().toString();
        var account = new UserAccount(id, "login-" + id, passwords.encode("test-password"), role,
                false, true, changeRequired, 0, 1, 1);
        accounts.insert(account);
        return account;
    }

    private record Csrf(MockHttpSession session, String header, String token) { }

    private Csrf csrf(MockHttpSession session) throws Exception {
        var request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        var result = mvc.perform(request).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsString());
        return new Csrf((MockHttpSession) result.getRequest().getSession(), body.path("headerName").asText(), body.path("token").asText());
    }

    private MockHttpServletRequestBuilder securedPost(String path, Csrf token, Object body) throws Exception {
        return post(path).session(token.session()).header(token.header(), token.token())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private MockHttpSession login(UserAccount account) throws Exception {
        return login(account, "test-password");
    }

    private MockHttpSession login(UserAccount account, String password) throws Exception {
        var result = mvc.perform(securedPost("/api/auth/login", csrf(null),
                        Map.of("username", account.username(), "password", password)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(account.role().code()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.sessionVersion").doesNotExist()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void shouldProtectPagesBusinessApiDownloadsAndDocsWhileAllowingLoginAssets() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login.html?next=%2F"));
        mvc.perform(get("/reports.html?id=demo")).andExpect(status().is3xxRedirection());
        for (String path : new String[]{"/api/auth/me", "/api/demo/capabilities", "/api/conversations",
                "/api/reports/orders/unknown/download", "/v3/api-docs"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isString());
        }
        mvc.perform(get("/login.html")).andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("登录你的账号"));
        mvc.perform(get("/js/auth.js")).andExpect(status().isOk());
        mvc.perform(get("/css/auth.css")).andExpect(status().isOk());
        verifyNoInteractions(model);
    }

    @Test
    void shouldLoginUsingSessionWithNormalizedUsernameAndRotateAnonymousSession() throws Exception {
        var account = account(Role.CUSTOMER_SERVICE, false);
        var before = csrf(null);
        String oldId = before.session().getId();
        var result = mvc.perform(securedPost("/api/auth/login", before,
                        Map.of("username", account.username().toUpperCase(java.util.Locale.ROOT), "password", "test-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value(account.username()))
                .andExpect(jsonPath("$.role").value("service")).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        assertThat(before.session().isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(account.id())).andExpect(jsonPath("$.administrator").value(false))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andExpect(jsonPath("$.version").doesNotExist());
        mvc.perform(get("/").session(session)).andExpect(status().isOk());
        mvc.perform(get("/api/demo/capabilities").session(session)).andExpect(status().isOk());
        verifyNoInteractions(model);
    }

    @Test
    void shouldRejectUnknownWrongAndDisabledCredentialsWithoutCreatingAuthenticatedSession() throws Exception {
        var account = account(Role.BUYER, false);
        for (var body : List.of(Map.of("username", "missing-user", "password", "test-password"),
                Map.of("username", account.username(), "password", "wrong-password"))) {
            var token = csrf(null);
            mvc.perform(securedPost("/api/auth/login", token, body)).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value("账号或密码错误，或账号已禁用"));
            mvc.perform(get("/api/auth/me").session(token.session())).andExpect(status().isUnauthorized());
        }
        jdbc.update("UPDATE user_accounts SET enabled=FALSE WHERE id=?", account.id());
        mvc.perform(securedPost("/api/auth/login", csrf(null), Map.of("username", account.username(), "password", "test-password")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value("账号或密码错误，或账号已禁用"));
        verifyNoInteractions(model);
    }

    @Test
    void shouldRejectMissingOrInvalidCsrfBeforeLoginAndBusinessWrites() throws Exception {
        var account = account(Role.BUYER, false);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("username", account.username(), "password", "test-password"))))
                .andExpect(status().isForbidden());
        var session = login(account);
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/chat").session(session).header("X-CSRF-TOKEN", "forged")
                .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"不应触发模型\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        verifyNoInteractions(model);
    }

    @Test
    void shouldNotEchoOrLogPasswordValuesFromInvalidRequests(CapturedOutput output) throws Exception {
        String secret = "sensitive-test-password-" + "x".repeat(80);
        var token = csrf(null);
        var result = mvc.perform(securedPost("/api/auth/login", token, Map.of("username", "buyer", "password", secret)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(secret, "rejectedValue", "passwordHash");
        mvc.perform(post("/api/auth/login").session(token.session()).header(token.header(), token.token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + secret + "\""))
                .andExpect(status().isBadRequest());
        assertThat(output.getAll()).doesNotContain(secret, "rejected value");
    }

    @Test
    void shouldRequireInitialPasswordChangeAndThenInvalidateEveryOldSession() throws Exception {
        var account = account(Role.WAREHOUSE, true);
        var first = login(account);
        var second = login(account);
        mvc.perform(get("/").session(first)).andExpect(redirectedUrl("/account.html"));
        mvc.perform(get("/account.html").session(first)).andExpect(status().isOk());
        mvc.perform(get("/api/demo/capabilities").session(first)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.passwordChangeRequired").value(true));
        mvc.perform(securedPost("/api/auth/password", csrf(first),
                        Map.of("currentPassword", "test-password", "newPassword", "new-password")))
                .andExpect(status().isNoContent());
        assertThat(first.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me").session(second)).andExpect(status().isUnauthorized());
        assertThat(second.isInvalid()).isTrue();
        var stored = accounts.findById(account.id()).orElseThrow();
        assertThat(stored.passwordChangeRequired()).isFalse();
        assertThat(stored.sessionVersion()).isEqualTo(1);
        assertThat(passwords.matches("new-password", stored.passwordHash())).isTrue();
        mvc.perform(securedPost("/api/auth/login", csrf(null), Map.of("username", account.username(), "password", "test-password")))
                .andExpect(status().isUnauthorized());
        var renewed = login(stored, "new-password");
        mvc.perform(get("/").session(renewed)).andExpect(status().isOk());
        verifyNoInteractions(model);
    }

    @Test
    void shouldValidatePasswordChangeWithoutChangingStateOnFailure() throws Exception {
        var account = account(Role.BUYER, false);
        var session = login(account);
        for (var body : List.of(Map.of("currentPassword", "wrong-password", "newPassword", "new-password"),
                Map.of("currentPassword", "test-password", "newPassword", "test-password"),
                Map.of("currentPassword", "test-password", "newPassword", "密".repeat(25)),
                Map.of("currentPassword", "test-password", "newPassword", "abc"))) {
            mvc.perform(securedPost("/api/auth/password", csrf(session), body)).andExpect(status().isBadRequest());
            assertThat(accounts.findById(account.id())).contains(account);
        }
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void shouldInvalidateSessionAfterDisableOrExternalPasswordResetVersionChange() throws Exception {
        var account = account(Role.CUSTOMER_SERVICE, false);
        var session = login(account);
        jdbc.update("UPDATE user_accounts SET enabled=FALSE WHERE id=?", account.id());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
        jdbc.update("UPDATE user_accounts SET enabled=TRUE WHERE id=?", account.id());
        var nextSession = login(account);
        var token = csrf(nextSession);
        jdbc.update("UPDATE user_accounts SET session_version=session_version+1 WHERE id=?", account.id());
        mvc.perform(securedPost("/api/chat", token, Map.of("message", "登录失效时不能调用模型")))
                .andExpect(status().isUnauthorized());
        assertThat(nextSession.isInvalid()).isTrue();
        verifyNoInteractions(model);
    }

    @Test
    void shouldLogoutOnlyCurrentSessionAndRequireAuthenticationAgain() throws Exception {
        var account = account(Role.BUYER, false);
        var first = login(account);
        var second = login(account);
        mvc.perform(securedPost("/api/auth/logout", csrf(first), Map.of())).andExpect(status().isNoContent());
        assertThat(first.isInvalid()).isTrue();
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").session(second)).andExpect(status().isOk());
    }

    @Test
    void shouldRejectStalePasswordUpdatesAtomically() {
        var account = account(Role.BUYER, false);
        var session = new AccountSession(account.id(), account.sessionVersion());
        authentication.changePassword(session, "test-password", "first-new-password");
        assertThatThrownBy(() -> authentication.changePassword(session, "test-password", "second-new-password"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(accounts.changePassword(account.id(), 0, account.passwordHash(), passwords.encode("second-new-password"))).isFalse();
        assertThat(passwords.matches("first-new-password", accounts.findById(account.id()).orElseThrow().passwordHash())).isTrue();
    }
}
