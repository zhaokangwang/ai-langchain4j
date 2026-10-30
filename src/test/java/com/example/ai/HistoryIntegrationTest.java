package com.example.ai;

import org.springframework.context.annotation.Import;

import com.example.ai.repository.ChatHistoryRepository;
import com.example.ai.service.CustomerChatService;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@AutoConfigureMockMvc
@Import(TestAuthenticatedRequests.class)
@WithBusinessIdentity
class HistoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired CustomerChatService chat;
    @Autowired ChatHistoryRepository history;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ChatModel model;
    private String id(){return UUID.randomUUID().toString();}
    private com.example.ai.chat.ChatResponse create(String conversation) {
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("已保存回答")).build());
        return chat.chat(conversation,"历史问题",id());
    }

    @Test void shouldListReadRenameArchiveAndRestoreWithoutCallingModel() throws Exception {
        var saved=create(id());clearInvocations(model);
        mvc.perform(get("/api/conversations")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/conversations/{id}/messages",saved.conversationId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].content").value("历史问题"))
                .andExpect(jsonPath("$.items[1].content").value("已保存回答"));
        mvc.perform(patch("/api/conversations/{id}",saved.conversationId()).contentType("application/json").content("{\"title\":\"键盘售后\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("键盘售后"));
        mvc.perform(patch("/api/conversations/{id}",saved.conversationId()).contentType("application/json").content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        String archived=mvc.perform(get("/api/conversations").param("status","ARCHIVED").param("size","100"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(archived).contains(saved.conversationId());
        assertThatThrownBy(()->chat.chat(saved.conversationId(),"归档后不允许发送",id())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(get("/api/conversations/{id}/messages",saved.conversationId())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/chat-runs/{id}",saved.requestId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.models[0].responseJson").isString()).andExpect(jsonPath("$.selectedAttempt").value(1));
        mvc.perform(patch("/api/conversations/{id}",saved.conversationId()).contentType("application/json").content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_events WHERE conversation_id=? AND event_type IN ('RENAME_CONVERSATION','ARCHIVE_CONVERSATION','RESTORE_CONVERSATION')",Integer.class,saved.conversationId())).isEqualTo(3);
        verifyNoInteractions(model);
    }

    @Test void shouldUseMessageCursorWithoutDuplicates() throws Exception {
        String conversation=id();create(conversation);create(conversation);clearInvocations(model);
        var first=mapper.readTree(mvc.perform(get("/api/conversations/{id}/messages",conversation).param("size","2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsString());
        var second=mapper.readTree(mvc.perform(get("/api/conversations/{id}/messages",conversation).param("size","2").param("beforeId",first.path("nextBeforeId").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasMore").value(false)).andReturn().getResponse().getContentAsString());
        assertThat(second.path("items").get(1).path("id").asLong()).isLessThan(first.path("items").get(0).path("id").asLong());
        verifyNoInteractions(model);
    }

    @Test void shouldRejectInvalidParametersAndHideOtherUsersAndLegacySources() throws Exception {
        var saved=create(id());clearInvocations(model);
        jdbc.update("UPDATE conversations SET owner_id='other-user' WHERE id=?",saved.conversationId());
        mvc.perform(get("/api/conversations/{id}/messages",saved.conversationId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/chat-runs/{id}",saved.requestId())).andExpect(status().isNotFound());
        mvc.perform(patch("/api/conversations/{id}",saved.conversationId()).contentType("application/json").content("{\"title\":\"越权\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/conversations").param("size","0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations").param("page","-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversations").param("status","INVALID")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/conversations/unknown").contentType("application/json").content("{\"status\":\"DELETED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/conversations/unknown").contentType("application/json").content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest());
        var legacy=history.begin(id(),null,"旧售后接口","AFTER_SALES:order-after-sales-review");
        mvc.perform(get("/api/conversations/{id}/messages",legacy.conversationId())).andExpect(status().isNotFound());
        verifyNoInteractions(model);
    }

    @Test void shouldRejectArchivingRunningConversationAndExposeFailedAttempts() throws Exception {
        String conversation=id(), request=id();
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("测试失败"))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("重试成功")).build());
        assertThatThrownBy(()->chat.chat(conversation,"失败消息",request)).isInstanceOf(IllegalStateException.class);
        mvc.perform(get("/api/chat-runs/{id}",request)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"));
        chat.chat(conversation,"失败消息",request);clearInvocations(model);
        mvc.perform(get("/api/chat-runs/{id}",request).param("attempt","1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptCount").value(2)).andExpect(jsonPath("$.models[0].status").value("FAILED"));
        mvc.perform(get("/api/chat-runs/{id}",request).param("attempt","3")).andExpect(status().isNotFound());
        history.begin(id(),conversation,"正在处理","CHAT");
        mvc.perform(patch("/api/conversations/{id}",conversation).contentType("application/json").content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isConflict());
        verifyNoInteractions(model);
    }
}
