package com.example.ai;

import com.example.ai.repository.AccountRepository;
import com.example.ai.security.DemoIdentity;
import com.example.ai.security.DemoIdentity.Role;
import com.example.ai.service.CustomerChatService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"langchain4j.open-ai.chat-model.api-key=test-key-not-used", "app.demo.account=warehouse"})
@AutoConfigureMockMvc
class RoleAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoIdentity identity;
    @Autowired CustomerChatService chat;
    @MockitoBean ChatModel model;
    RoleTestSupport users;

    @BeforeEach
    void setup() { users = new RoleTestSupport(accounts, passwords, mvc, mapper); }

    private String id() { return UUID.randomUUID().toString(); }
    private ChatResponse answer(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }
    private ChatResponse tool(String name, String arguments) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id(id()).name(name).arguments(arguments).build())).build();
    }
    private JsonNode body(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void shouldUseLoginRoleForCapabilitiesAndIgnoreStartupHeadersAndModelRoleClaims() throws Exception {
        for (Role role : Role.values()) {
            var user = users.account(role);
            var session = users.login(user);
            mvc.perform(get("/api/demo/capabilities").session(session).header("X-Role", "WAREHOUSE").param("role", "WAREHOUSE"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.account.role").value(role.name()))
                    .andExpect(jsonPath("$.actor.id").value(user.id())).andExpect(jsonPath("$.actor.username").value(user.username()))
                    .andExpect(jsonPath("$.skills.length()").value(role == Role.BUYER ? 6 : role == Role.CUSTOMER_SERVICE ? 8 : 3))
                    .andExpect(jsonPath("$.actor.sessionVersion").doesNotExist());
        }
        verifyNoInteractions(model);
    }

    @Test
    void shouldShareConversationReplayRenameArchiveAndAuditWithinRole() throws Exception {
        var firstUser = users.account(Role.BUYER);
        var first = users.login(firstUser);
        var peer = users.login(users.account(Role.BUYER));
        String request = id();
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("同角色共享回答"));
        var saved = body(mvc.perform(users.json(post("/api/chat"), first,
                Map.of("message", "解释 Java record", "requestId", request))).andExpect(status().isOk()).andReturn());
        String conversation = saved.path("conversationId").asText();
        mvc.perform(get("/api/conversations/{id}/messages", conversation).session(peer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[1].content").value("同角色共享回答"));
        var replay = body(mvc.perform(users.json(post("/api/chat"), peer,
                Map.of("message", "解释 Java record", "requestId", request))).andExpect(status().isOk()).andReturn());
        assertThat(replay).isEqualTo(saved);
        mvc.perform(users.json(patch("/api/conversations/{id}", conversation), peer, Map.of("title", "共享会话")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("共享会话"));
        mvc.perform(users.json(patch("/api/conversations/{id}", conversation), peer, Map.of("status", "ARCHIVED"))).andExpect(status().isOk());
        mvc.perform(users.json(patch("/api/conversations/{id}", conversation), first, Map.of("status", "ACTIVE"))).andExpect(status().isOk());
        mvc.perform(get("/api/chat-runs/{id}", request).session(peer)).andExpect(status().isOk());
        var authorization = mapper.readTree(jdbc.queryForObject(
                "SELECT details FROM operation_events WHERE request_id=? AND event_type='AUTHORIZATION'", String.class, request));
        assertThat(authorization.path("actor").path("id").asText()).isEqualTo(firstUser.id());
        assertThat(authorization.path("account").path("id").asText()).isEqualTo("demo-user");
        verify(model).chat(any(ChatRequest.class));
    }

    @Test
    void shouldRejectCrossRoleHistoryReplayAndUpdatesWithoutCallingModelAgain() throws Exception {
        var buyer = users.login(users.account(Role.BUYER));
        var warehouse = users.login(users.account(Role.WAREHOUSE));
        String request = id();
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("买家共享记录"));
        String conversation = body(mvc.perform(users.json(post("/api/chat"), buyer,
                Map.of("message", "解释 Java record", "requestId", request))).andExpect(status().isOk()).andReturn()).path("conversationId").asText();
        clearInvocations(model);
        mvc.perform(get("/api/conversations/{id}/messages", conversation).session(warehouse)).andExpect(status().isNotFound());
        mvc.perform(get("/api/chat-runs/{id}", request).session(warehouse)).andExpect(status().isNotFound());
        mvc.perform(users.json(patch("/api/conversations/{id}", conversation), warehouse, Map.of("title", "不应修改"))).andExpect(status().isNotFound());
        mvc.perform(users.json(post("/api/chat"), warehouse, Map.of("message", "解释 Java record", "requestId", request)))
                .andExpect(status().isConflict());
        var listing = body(mvc.perform(get("/api/conversations").session(warehouse)).andExpect(status().isOk()).andReturn());
        assertThat(listing.toString()).doesNotContain(conversation);
        verifyNoInteractions(model);
    }

    @Test
    void shouldShareReportsAndDownloadsWithinEachRoleAndHideThemFromOtherRoles() throws Exception {
        var filter = Map.of("startDate", "2026-10-01", "endDate", "2026-10-31", "status", "", "productKeyword", "", "groupBy", "PRODUCT");
        for (Role role : Role.values()) {
            var first = users.login(users.account(role));
            var peer = users.login(users.account(role));
            var other = users.login(users.account(role == Role.BUYER ? Role.WAREHOUSE : Role.BUYER));
            var report = body(mvc.perform(users.json(post("/api/reports/orders"), first, filter)).andExpect(status().isOk()).andReturn());
            String reportId = report.path("reportId").asText();
            assertThat(report.path("summary").path("orderCount").asInt()).isEqualTo(role == Role.BUYER ? 3 : 1);
            if (role != Role.BUYER) assertThat(report.path("rows").get(0).path("orderNumber").asText())
                    .isEqualTo(role == Role.CUSTOMER_SERVICE ? "A1001" : "A1002");
            mvc.perform(get("/api/reports/orders/{id}", reportId).session(peer)).andExpect(status().isOk());
            mvc.perform(get("/api/reports/orders/{id}/download", reportId).session(peer)).andExpect(status().isOk())
                    .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            mvc.perform(get("/api/reports/orders/{id}", reportId).session(other)).andExpect(status().isNotFound());
            mvc.perform(get("/api/reports/orders/{id}/download", reportId).session(other)).andExpect(status().isNotFound());
        }
        verifyNoInteractions(model);
    }

    @Test
    void shouldEnforceSkillAndToolPermissionsForRealBuyerLogin() throws Exception {
        var buyer = users.login(users.account(Role.BUYER));
        String request = id();
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("loadSkill", "{\"skillName\":\"warehouse-stock-check\"}"),
                tool("getWarehouseStock", "{}"), answer("当前角色没有仓库权限"));
        mvc.perform(users.json(post("/api/chat"), buyer, Map.of("message", "订单 A1001 想退货", "requestId", request)))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE request_id=? AND status='FAILED'", Integer.class, request)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE request_id=? AND status='COMPLETED'", Integer.class, request)).isZero();
    }

    @Test
    void shouldExecuteWarehouseSkillWithWarehouseDataAndNotBroadenAdminBusinessPermissions() throws Exception {
        var warehouse = users.login(users.account(Role.WAREHOUSE, true));
        String request = id();
        mvc.perform(get("/api/demo/capabilities").session(warehouse)).andExpect(jsonPath("$.skills.length()").value(3));
        when(model.chat(any(ChatRequest.class))).thenReturn(tool("loadSkill", "{\"skillName\":\"warehouse-stock-check\"}"),
                tool("getWarehouseStock", "{}"), answer("WH01 库存待复核"));
        mvc.perform(users.json(post("/api/chat"), warehouse, Map.of("message", "查询仓库库存", "requestId", request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.executedTools[1]").value("getWarehouseStock"));
        assertThat(jdbc.queryForObject("SELECT result_text FROM tool_calls WHERE request_id=? AND tool_name='getWarehouseStock'", String.class, request))
                .contains("WH01", "A1002").doesNotContain("A1001");
        verify(model, times(3)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldApplyLoginRoleToLegacyAfterSalesAndKeepOldRoleOwnedDataReadable() throws Exception {
        var staff = users.login(users.account(Role.CUSTOMER_SERVICE));
        var warehouse = users.login(users.account(Role.WAREHOUSE));
        String conversation = id();
        jdbc.update("INSERT INTO conversations(id,owner_id,title,source,created_at,updated_at) VALUES(?,'demo-service','旧客服共享记录','CHAT',1,1)", conversation);
        mvc.perform(get("/api/conversations/{id}/messages", conversation).session(staff)).andExpect(status().isOk());
        mvc.perform(get("/api/conversations/{id}/messages", conversation).session(warehouse)).andExpect(status().isNotFound());
        mvc.perform(users.json(post("/api/skills/after-sales"), warehouse, Map.of("message", "审核售后"))).andExpect(status().isForbidden());
        verifyNoInteractions(model);
    }

    @Test
    void shouldRejectManagedBusinessAccessWithoutAuthenticatedSnapshot() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThatThrownBy(identity::current).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("401");
        assertThatThrownBy(() -> chat.chat(null, "解释 Java", id()))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("401");
        verifyNoInteractions(model);
    }

    @Test
    void shouldStopBeforeToolsOrFinalCommitWhenAccountBecomesDisabledDuringModelCall() throws Exception {
        var user = users.account(Role.BUYER);
        var session = users.login(user);
        String request = id();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            jdbc.update("UPDATE user_accounts SET enabled=FALSE WHERE id=?", user.id());
            return tool("loadSkill", "{\"skillName\":\"return-policy-guide\"}");
        });
        mvc.perform(users.json(post("/api/chat"), session, Map.of("message", "订单 A1001 想退货", "requestId", request)))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE request_id=?", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE request_id=? AND role='ASSISTANT'", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_runs WHERE request_id=?", String.class, request)).isEqualTo("FAILED");
    }
}
