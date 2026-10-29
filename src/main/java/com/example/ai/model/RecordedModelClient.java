package com.example.ai.model;

import com.example.ai.stream.ChatStream;
import com.example.ai.repository.ChatHistoryRepository;

import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import com.example.ai.repository.ExecutionAuditRepository;
import org.springframework.stereotype.Component;

import java.util.*;

/** 只记录消息与工具定义，不读取 API Key、HTTP 认证头或网络请求配置。 */
@Component
public class RecordedModelClient {
    private final ChatModel model;
    private final ExecutionAuditRepository audit;
    private final ChatHistoryRepository history;

    public RecordedModelClient(ChatModel model, ExecutionAuditRepository audit, ChatHistoryRepository history) {
        this.model = model;
        this.audit = audit;
        this.history = history;
    }

    public ChatResponse chat(ChatHistoryRepository.Run run, int round, ChatRequest request) {
        String id = start(run, round, request);
        try {
            ChatResponse response = model.chat(request);
            complete(id, response);
            return response;
        } catch (RuntimeException error) {
            fail(id, error, null);
            throw error;
        }
    }

    String start(ChatHistoryRepository.Run run, int round, ChatRequest request) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("messages", ChatMessageSerializer.messagesToJson(request.messages()));
        snapshot.put("tools", request.toolSpecifications() == null ? List.of() : request.toolSpecifications().stream().map(tool -> tool.toJson()).toList());
        audit.startModel(id, run, round, history.json(snapshot));
        return id;
    }

    void complete(String id, ChatResponse response) {
            if (response == null || response.aiMessage() == null) throw new IllegalStateException("模型未返回有效消息");
            var metadata = response.metadata();
            var tokens = metadata == null ? null : metadata.tokenUsage();
            audit.completeModel(id, new ExecutionAuditRepository.ModelResult(
                    ChatMessageSerializer.messageToJson(response.aiMessage()), metadata == null ? null : metadata.modelName(),
                    tokens == null ? null : tokens.inputTokenCount(), tokens == null ? null : tokens.outputTokenCount(),
                    tokens == null ? null : tokens.totalTokenCount()));
    }

    void fail(String id, RuntimeException error, String partialJson) {
        try {
            String status = error instanceof org.springframework.dao.DataAccessException || error instanceof ChatStream.Interrupted
                    ? "INTERRUPTED" : "FAILED";
            audit.failModel(id, status, error.getClass().getSimpleName(), partialJson);
        } catch (RuntimeException recordingError) {
            error.addSuppressed(recordingError);
        }
    }
}
