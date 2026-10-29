package com.example.ai.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Each audit mutation is a short database operation outside model/network waits. */
@Repository
public class ExecutionAuditRepository {
    private final JdbcTemplate jdbc;

    public ExecutionAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ModelResult(String responseJson, String modelName, Integer inputTokens,
                              Integer outputTokens, Integer totalTokens) {}

    public record ToolResult(String status, String text, String skillName, String skillHash, String errorType) {}

    public void startModel(String id, ChatHistoryRepository.Run run, int round, String requestJson) {
        jdbc.update("INSERT INTO model_calls(id,request_id,attempt_no,round_no,status,request_json,started_at) VALUES(?,?,?,?,'RUNNING',?,?)",
                id, run.requestId(), run.attempt(), round, requestJson, System.currentTimeMillis());
    }

    public void completeModel(String id, ModelResult result) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE model_calls SET status='COMPLETED',response_json=?,model_name=?,input_tokens=?,
                output_tokens=?,total_tokens=?,finished_at=?,duration_ms=?-started_at WHERE id=?
                """, result.responseJson(), result.modelName(), result.inputTokens(), result.outputTokens(),
                result.totalTokens(), now, now, id);
    }

    public void failModel(String id, String status, String errorType, String partialJson) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE model_calls SET status=?,error_type=?,response_json=COALESCE(?,response_json),
                finished_at=?,duration_ms=?-started_at WHERE id=? AND status='RUNNING'
                """, status, errorType, partialJson, now, now, id);
    }

    public void startTool(String id, ChatHistoryRepository.Run run, int round, String callId, String name, String arguments) {
        jdbc.update("INSERT INTO tool_calls(id,request_id,attempt_no,round_no,call_id,tool_name,arguments_json,status,started_at) VALUES(?,?,?,?,?,?,?,'RUNNING',?)",
                id, run.requestId(), run.attempt(), round, callId, name, arguments, System.currentTimeMillis());
    }

    public void completeTool(String id, ToolResult result) {
        long now = System.currentTimeMillis();
        jdbc.update("""
                UPDATE tool_calls SET status=?,result_text=?,skill_name=?,skill_sha256=?,error_type=?,
                finished_at=?,duration_ms=?-started_at WHERE id=?
                """, result.status(), result.text(), result.skillName(), result.skillHash(), result.errorType(), now, now, id);
    }

    public void failTool(String id, String status, String errorType) {
        long now = System.currentTimeMillis();
        jdbc.update("UPDATE tool_calls SET status=?,error_type=?,finished_at=?,duration_ms=?-started_at WHERE id=?",
                status, errorType, now, now, id);
    }

    public void event(ChatHistoryRepository.Run run, String type, String details) {
        jdbc.update("INSERT INTO operation_events(conversation_id,request_id,event_type,details,created_at,attempt_no) VALUES(?,?,?,?,?,?)",
                run.conversationId(), run.requestId(), type, details, System.currentTimeMillis(), run.attempt());
    }
}
