package com.example.ai;

import java.util.List;

import com.example.ai.repository.ChatHistoryRepository;
import com.example.ai.service.CustomerChatService;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@WithBusinessIdentity
class ChatRecordingTest {
    @Autowired CustomerChatService service;
    @Autowired ChatHistoryRepository history;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ChatModel model;

    private String id() { return UUID.randomUUID().toString(); }
    private ChatResponse answer(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }

    @Test
    void shouldRecordModelMetadataAndKeepTransportCredentialsOutOfSnapshots() {
        String request = id();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("回答正文"))
                .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                        .modelName("demo-model").tokenUsage(new dev.langchain4j.model.output.TokenUsage(20, 8)).build()).build());
        service.chat(null, "记录元信息", request);
        assertThat(jdbc.queryForObject("SELECT model_name FROM model_calls WHERE request_id=?", String.class, request)).isEqualTo("demo-model");
        assertThat(jdbc.queryForObject("SELECT total_tokens FROM model_calls WHERE request_id=?", Integer.class, request)).isEqualTo(28);
        assertThat(jdbc.queryForObject("SELECT response_json FROM model_calls WHERE request_id=?", String.class, request)).contains("回答正文");
        assertThat(jdbc.queryForObject("SELECT request_json FROM model_calls WHERE request_id=?", String.class, request))
                .contains("记录元信息").doesNotContain("test-key-not-used", "Authorization", "DEEPSEEK_API_KEY");
    }

    @Test
    void shouldOrderRetriedTurnsByCompletionInsteadOfOriginalSubmission() {
        String conversation = id();
        String first = id();
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("首次失败"))
                .thenReturn(answer("第二条完成"), answer("第一条重试完成"));
        assertThatThrownBy(() -> service.chat(conversation, "先发但失败", first)).isInstanceOf(IllegalStateException.class);
        service.chat(conversation, "后发先成功", id());
        service.chat(conversation, "先发但失败", first);
        var context = history.recentContext(conversation);
        assertThat(((dev.langchain4j.data.message.UserMessage)context.get(0)).singleText()).isEqualTo("后发先成功");
        assertThat(((dev.langchain4j.data.message.UserMessage)context.get(2)).singleText()).isEqualTo("先发但失败");
    }

    @Test
    void shouldReuseCompletedRequestAndRejectDifferentPayload() {
        String request = id();
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("真实保存的回答"));
        var first = service.chat(null, "问题", request);
        assertThat(service.chat(null, "问题", request)).isEqualTo(first);
        assertThatThrownBy(() -> service.chat(null, "不同问题", request)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.chat(id(), "问题", request)).isInstanceOf(ResponseStatusException.class);
        verify(model).chat(any(ChatRequest.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE request_id=?", Integer.class, request)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_events WHERE request_id=? AND event_type='REPLAY_COMPLETED'", Integer.class, request)).isEqualTo(1);
    }

    @Test
    void shouldSaveFailureAndRetryWithoutDuplicatingUserMessage() {
        String request = id();
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("not persisted secret detail"))
                .thenReturn(answer("重试成功"));
        assertThatThrownBy(() -> service.chat(null, "重试问题", request)).isInstanceOf(IllegalStateException.class);
        assertThat(history.find(request).orElseThrow().status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_type FROM model_calls WHERE request_id=?", String.class, request)).isEqualTo("IllegalStateException");
        var result = service.chat(null, "重试问题", request);
        assertThat(result.answer()).isEqualTo("重试成功");
        assertThat(history.find(request).orElseThrow().attempt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE request_id=? AND role='USER'", Integer.class, request)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT status FROM model_calls WHERE request_id=? ORDER BY attempt_no", String.class, request)).containsExactly("FAILED", "COMPLETED");
    }

    @Test
    void shouldExcludeFailedTurnsAndAvoidTransactionDuringModelCall() {
        String conversation = id();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new IllegalStateException("模拟失败");
        });
        assertThatThrownBy(() -> service.chat(conversation, "不完整失败消息", id())).isInstanceOf(IllegalStateException.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("正常回答"));
        service.chat(conversation, "新消息", id());
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        assertThat(captor.getAllValues().get(1).messages()).hasSize(2);
        assertThat(captor.getAllValues().get(1).messages().toString()).doesNotContain("不完整失败消息");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_runs WHERE conversation_id=?", Integer.class, conversation)).isEqualTo(2);
    }

    @Test
    void shouldArchiveAllTurnsButOnlySupplySixCompleteTurnsToModel() {
        String conversation = id();
        when(model.chat(any(ChatRequest.class))).thenReturn(answer("回答"));
        for (int i = 1; i <= 9; i++) service.chat(conversation, "message-" + i, id());
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(9)).chat(captor.capture());
        var last = captor.getAllValues().get(8).messages();
        assertThat(last).hasSize(14);
        assertThat(last.toString()).contains("message-3", "message-9").doesNotContain("message-1", "message-2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_runs WHERE conversation_id=? AND status='COMPLETED'", Integer.class, conversation)).isEqualTo(9);
    }

    @Test
    void shouldRejectConcurrentRequestsForSameConversation() throws Exception {
        String conversation = id();
        String request = id();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试等待超时");
            return answer("完成");
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var captured = service.prepareChat(conversation, "问题", request, null);
            var first = executor.submit(captured::get);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.chat(conversation, "问题", request)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> service.chat(conversation, "另一个问题", id())).isInstanceOf(ResponseStatusException.class);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).answer()).isEqualTo("完成");
            verify(model).chat(any(ChatRequest.class));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    void shouldRecoverMultipleBatchesOnceWithoutChangingCompletedRequests() {
        String conversation = id();
        String prefix = id();
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO conversations(id,owner_id,title,source,created_at,updated_at) VALUES(?,'demo-user','批量恢复','CHAT',?,?)",
                conversation, now, now);
        List<Object[]> runs = java.util.stream.IntStream.range(0, 1005).mapToObj(index -> new Object[] {
            prefix + "-" + String.format("%04d", index), conversation, now, now
        }).toList();
        String insert = "INSERT INTO chat_runs(request_id,conversation_id,input_text,status,attempt_count,created_at,started_at) VALUES(?,?,'未完成','RUNNING',1,?,?)";
        jdbc.batchUpdate(insert, runs);
        String completed = prefix + "-done";
        jdbc.update(insert.replace("'RUNNING'", "'COMPLETED'"), completed, conversation, now, now);

        history.recoverInterrupted();
        history.recoverInterrupted();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_runs WHERE conversation_id=? AND status='INTERRUPTED'",
                Integer.class, conversation)).isEqualTo(1005);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_events WHERE conversation_id=? AND event_type='RECOVER_INTERRUPTED'",
                Integer.class, conversation)).isEqualTo(1005);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT request_id) FROM operation_events WHERE conversation_id=? AND event_type='RECOVER_INTERRUPTED'",
                Integer.class, conversation)).isEqualTo(1005);
        assertThat(history.find(completed).orElseThrow().status()).isEqualTo("COMPLETED");
        verifyNoInteractions(model);
    }

    @Test
    void shouldRecoverRunningRecordsAndNeverAutomaticallyReplayInterruptedRequest() {
        String request = id();
        var run = history.begin(request, null, "未完成", "CHAT");
        long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO model_calls(id,request_id,attempt_no,round_no,status,request_json,started_at) VALUES(?,?,1,1,'RUNNING','{}',?)", id(), request, now);
        jdbc.update("INSERT INTO tool_calls(id,request_id,attempt_no,round_no,tool_name,status,started_at) VALUES(?,?,1,1,'findMyOrders','RUNNING',?)", id(), request, now);
        history.recoverInterrupted();
        assertThat(history.find(request).orElseThrow().status()).isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT status FROM model_calls WHERE request_id=?", String.class, request)).isEqualTo("INTERRUPTED");
        assertThat(jdbc.queryForObject("SELECT status FROM tool_calls WHERE request_id=?", String.class, request)).isEqualTo("INTERRUPTED");
        assertThat(history.recentContext(run.conversationId())).isEmpty();
        assertThatThrownBy(() -> service.chat(null, "未完成", request)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(model);
    }
}
