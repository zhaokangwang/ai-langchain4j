package com.example.ai;

import com.example.ai.repository.AccountRepository;
import com.example.ai.security.DemoIdentity;
import com.example.ai.security.DemoIdentity.Role;
import com.example.ai.service.CustomerChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatModelStreamingEvent;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@AutoConfigureMockMvc
class RoleStreamingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DemoIdentity identity;
    @Autowired CustomerChatService service;
    @MockitoBean ChatModel blockingModel;
    @MockitoBean StreamingChatModel streamingModel;
    RoleTestSupport users;

    @BeforeEach
    void setup() { users = new RoleTestSupport(accounts, passwords, mvc, mapper); }
    private String id() { return UUID.randomUUID().toString(); }
    private ChatResponse answer(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }

    private MvcResult start(MockHttpSession session, String request) throws Exception {
        return mvc.perform(users.json(post("/api/chat/stream"), session,
                        Map.of("message", "解释 Java record", "requestId", request, "conversationId", request)))
                .andExpect(request().asyncStarted()).andReturn();
    }

    private String finish(MvcResult result) throws Exception {
        result.getAsyncResult(10_000);
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Flow.Publisher<ChatModelStreamingEvent> waiting(CountDownLatch started, AtomicBoolean cancelled) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long count) { subscriber.onNext(new PartialResponse("临时片段")); started.countDown(); }
            public void cancel() { cancelled.set(true); }
        });
    }

    @Test
    void shouldKeepConcurrentBuyerAndWarehouseStreamsInSeparateRoleAndActorContexts() throws Exception {
        var buyer = users.account(Role.BUYER);
        var warehouse = users.account(Role.WAREHOUSE);
        var buyerSession = users.login(buyer);
        var warehouseSession = users.login(warehouse);
        var subscribers = new ConcurrentHashMap<Role, Flow.Subscriber<? super ChatModelStreamingEvent>>();
        var actors = new ConcurrentHashMap<Role, String>();
        var started = new CountDownLatch(2);
        when(streamingModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Role role = identity.current().role();
            actors.put(role, identity.actor().id());
            return (Flow.Publisher<ChatModelStreamingEvent>) subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
                public void request(long count) { subscribers.put(role, subscriber); started.countDown(); }
                public void cancel() { }
            });
        });
        String buyerRequest = id(), warehouseRequest = id();
        // 分段锁允许无关请求发生哈希冲突；本测试显式选择两个不同的锁分段。
        while (Math.floorMod(buyerRequest.hashCode(), 64) == Math.floorMod(warehouseRequest.hashCode(), 64)) warehouseRequest = id();
        var buyerResult = start(buyerSession, buyerRequest);
        var warehouseResult = start(warehouseSession, warehouseRequest);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        subscribers.get(Role.WAREHOUSE).onNext(new CompleteResponse(answer("仓库角色回复")));
        subscribers.get(Role.BUYER).onNext(new CompleteResponse(answer("买家角色回复")));
        assertThat(finish(warehouseResult)).contains("WAREHOUSE", warehouse.id(), "仓库角色回复", "event:done");
        assertThat(finish(buyerResult)).contains("BUYER", buyer.id(), "买家角色回复", "event:done");
        assertThat(actors).containsEntry(Role.BUYER, buyer.id()).containsEntry(Role.WAREHOUSE, warehouse.id());
        assertThat(jdbc.queryForObject("SELECT c.owner_id FROM conversations c JOIN chat_runs r ON r.conversation_id=c.id WHERE r.request_id=?", String.class, buyerRequest))
                .isEqualTo("demo-user");
        assertThat(jdbc.queryForObject("SELECT c.owner_id FROM conversations c JOIN chat_runs r ON r.conversation_id=c.id WHERE r.request_id=?", String.class, warehouseRequest))
                .isEqualTo("demo-warehouse");
        verifyNoInteractions(blockingModel);
    }

    @Test
    void shouldOnlyAllowCancellationFromTheSameRoleIncludingAnotherAccount() throws Exception {
        var buyer = users.login(users.account(Role.BUYER));
        var peer = users.login(users.account(Role.BUYER));
        var warehouse = users.login(users.account(Role.WAREHOUSE));
        var started = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        when(streamingModel.chat(any(ChatRequest.class))).thenReturn(waiting(started, cancelled));
        String request = id();
        var result = start(buyer, request);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        mvc.perform(users.json(post("/api/chat/stream/{id}/cancel", request), warehouse, Map.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelRequested").value(false));
        assertThat(cancelled).isFalse();
        mvc.perform(users.json(post("/api/chat/stream/{id}/cancel", request), peer, Map.of()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cancelRequested").value(true));
        assertThat(finish(result)).contains("event:error", "499").doesNotContain("event:done");
        assertThat(cancelled).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_runs WHERE request_id=?", String.class, request)).isEqualTo("INTERRUPTED");
        mvc.perform(users.json(post("/api/chat"), peer, Map.of("message", "解释 Java record", "requestId", request)))
                .andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"disable", "password", "role"})
    void shouldInterruptWaitingStreamAfterAccountRevocationAndNeverCommitFinalAnswer(String change) throws Exception {
        var user = users.account(Role.BUYER);
        var session = users.login(user);
        var started = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        when(streamingModel.chat(any(ChatRequest.class))).thenReturn(waiting(started, cancelled));
        String request = id();
        var result = start(session, request);
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
        switch (change) {
            case "disable" -> jdbc.update("UPDATE user_accounts SET enabled=FALSE WHERE id=?", user.id());
            case "password" -> jdbc.update("UPDATE user_accounts SET session_version=session_version+1 WHERE id=?", user.id());
            case "role" -> jdbc.update("UPDATE user_accounts SET role='warehouse' WHERE id=?", user.id());
            default -> throw new IllegalArgumentException("未知测试变更");
        }
        assertThat(finish(result)).contains("event:error", "499").doesNotContain("event:done");
        assertThat(cancelled).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM chat_runs WHERE request_id=?", String.class, request)).isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE request_id=? AND role='ASSISTANT'", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE request_id=?", Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("SELECT response_json FROM model_calls WHERE request_id=?", String.class, request)).contains("incomplete");
    }

    @Test
    void shouldClearWorkerIdentityAfterSuccessAndFailureBeforeReusingThreadForAnotherRole() throws Exception {
        var buyer = users.account(Role.BUYER);
        var warehouse = users.account(Role.WAREHOUSE);
        when(blockingModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> answer(identity.current().role().code()));
        var buyerTask = RoleTestSupport.as(buyer, () -> service.prepareChat(null, "解释 Java record", id(), null));
        var warehouseTask = RoleTestSupport.as(warehouse, () -> service.prepareChat(null, "解释 Java record", id(), null));
        var executor = Executors.newSingleThreadExecutor();
        try {
            assertThat(executor.submit(buyerTask::get).get(10, TimeUnit.SECONDS).answer()).isEqualTo("buyer");
            assertThat(executor.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get()).isNull();
            jdbc.update("UPDATE user_accounts SET enabled=FALSE WHERE id=?", buyer.id());
            assertThatThrownBy(() -> executor.submit(buyerTask::get).get(10, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            assertThat(executor.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get()).isNull();
            assertThat(executor.submit(warehouseTask::get).get(10, TimeUnit.SECONDS).answer()).isEqualTo("warehouse");
            assertThat(executor.submit(() -> SecurityContextHolder.getContext().getAuthentication()).get()).isNull();
        } finally { executor.shutdownNow(); }
    }
}
