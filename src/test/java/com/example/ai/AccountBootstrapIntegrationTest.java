package com.example.ai;

import org.springframework.context.annotation.Import;

import com.example.ai.security.DemoIdentity;
import com.example.ai.service.AccountService;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@AutoConfigureMockMvc
@Import(TestAuthenticatedRequests.class)
@WithBusinessIdentity
class AccountBootstrapIntegrationTest {
    @Autowired AccountService accounts;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired DemoIdentity identity;
    @Autowired MockMvc mvc;
    @MockitoBean ChatModel model;

    @Test
    void shouldBootstrapAfterFlywayAndServeCapabilitiesToAuthenticatedAccount() throws Exception {
        var administrator = accounts.findByUsername("admin").orElseThrow();
        assertThat(passwordEncoder.matches("admin", administrator.passwordHash())).isTrue();
        assertThat(administrator.administrator()).isTrue();
        assertThat(administrator.passwordChangeRequired()).isTrue();
        assertThat(identity.ownerId()).isEqualTo("demo-user");
        mvc.perform(get("/api/demo/capabilities")).andExpect(status().isOk());
        verifyNoInteractions(model);
    }
}
