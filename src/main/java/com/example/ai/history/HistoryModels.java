package com.example.ai.history;

import java.util.List;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class HistoryModels {
    private HistoryModels() {
    }

    public record Change(@Size(max = 100) String title, @Pattern(regexp = "ACTIVE|ARCHIVED") String status) {}

    public record Conversation(String id, String title, String status, long createdAt, long updatedAt) {}
    public record ConversationPage(List<Conversation> items, int page, boolean hasMore) {}
    public record Message(long id, String requestId, String role, String content, String status, int attemptCount, long createdAt) {}
    public record MessagePage(Conversation conversation, List<Message> items, boolean hasMore, Long nextBeforeId) {}
    public record ModelCall(String id, int round, String status, String modelName, Integer inputTokens, Integer outputTokens,
                            Integer totalTokens, Long durationMs, String requestJson, String responseJson, String errorType, long startedAt) {}
    public record ToolCall(String id, int round, String name, String status, String argumentsJson, String resultText,
                           String skillName, String skillSha256, Long durationMs, String errorType, long startedAt) {}
    public record Event(String type, String details, long createdAt, Integer attemptNo) {}
    public record RunDetail(String requestId, String conversationId, String status, int attemptCount, int selectedAttempt,
                            String input, String errorType, Long durationMs, List<ModelCall> models, List<ToolCall> tools, List<Event> events) {}

}
