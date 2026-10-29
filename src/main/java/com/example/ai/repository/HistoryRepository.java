package com.example.ai.repository;

import com.example.ai.history.HistoryModels.Conversation;
import com.example.ai.history.HistoryModels.Event;
import com.example.ai.history.HistoryModels.Message;
import com.example.ai.history.HistoryModels.ModelCall;
import com.example.ai.history.HistoryModels.ToolCall;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryRepository {
    private final JdbcTemplate jdbc;

    public HistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RunRecord(String conversation, String status, int attempts, String input,
                            String error, Long duration) {}

    public List<Conversation> conversations(String owner, String status, int limit, int offset) {
        return jdbc.query("""
                SELECT id,title,status,created_at,updated_at FROM conversations
                WHERE owner_id=? AND source='CHAT' AND status=?
                ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?
                """, (rs, n) -> conversation(rs), owner, status, limit, offset);
    }

    public Optional<Conversation> conversation(String id, String owner, boolean lock) {
        String sql = """
                SELECT id,title,status,created_at,updated_at FROM conversations
                WHERE id=? AND owner_id=? AND source='CHAT'
                """ + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, n) -> conversation(rs), id, owner).stream().findFirst();
    }

    public List<Message> messages(String id, long before, int limit) {
        return jdbc.query("""
                SELECT m.id,m.request_id,m.role,m.content,m.created_at,r.status,r.attempt_count
                FROM chat_messages m JOIN chat_runs r ON m.request_id=r.request_id
                WHERE r.conversation_id=? AND m.id<? ORDER BY m.id DESC LIMIT ?
                """, (rs, n) -> new Message(rs.getLong("id"), rs.getString("request_id"),
                rs.getString("role"), rs.getString("content"), rs.getString("status"),
                rs.getInt("attempt_count"), rs.getLong("created_at")), id, before, limit);
    }

    public boolean hasRunningRequests(String id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM chat_runs WHERE conversation_id=? AND status='RUNNING'", Integer.class, id);
        return count != null && count > 0;
    }

    public void change(String id, String title, String status) {
        jdbc.update("UPDATE conversations SET title=?,status=?,updated_at=? WHERE id=?",
                title, status, System.currentTimeMillis(), id);
    }

    public void event(String id, String type, String detail) {
        jdbc.update("INSERT INTO operation_events(conversation_id,event_type,details,created_at) VALUES(?,?,?,?)",
                id, type, detail, System.currentTimeMillis());
    }

    public Optional<RunRecord> run(String requestId, String owner) {
        return jdbc.query("""
                SELECT r.conversation_id,r.status,r.attempt_count,r.input_text,r.error_type,r.duration_ms
                FROM chat_runs r JOIN conversations c ON c.id=r.conversation_id
                WHERE r.request_id=? AND c.owner_id=? AND c.source='CHAT'
                """, (rs, n) -> new RunRecord(rs.getString("conversation_id"), rs.getString("status"),
                rs.getInt("attempt_count"), rs.getString("input_text"), rs.getString("error_type"),
                rs.getObject("duration_ms", Long.class)), requestId, owner).stream().findFirst();
    }

    public List<ModelCall> models(String requestId, int attempt) {
        return jdbc.query("""
                SELECT id,round_no,status,model_name,input_tokens,output_tokens,total_tokens,
                       duration_ms,request_json,response_json,error_type,started_at
                FROM model_calls WHERE request_id=? AND attempt_no=? ORDER BY round_no,started_at,id
                """, (rs, n) -> new ModelCall(rs.getString("id"), rs.getInt("round_no"), rs.getString("status"),
                rs.getString("model_name"), rs.getObject("input_tokens", Integer.class),
                rs.getObject("output_tokens", Integer.class), rs.getObject("total_tokens", Integer.class),
                rs.getObject("duration_ms", Long.class), rs.getString("request_json"), rs.getString("response_json"),
                rs.getString("error_type"), rs.getLong("started_at")), requestId, attempt);
    }

    public List<ToolCall> tools(String requestId, int attempt) {
        return jdbc.query("""
                SELECT id,round_no,tool_name,status,arguments_json,result_text,skill_name,skill_sha256,
                       duration_ms,error_type,started_at
                FROM tool_calls WHERE request_id=? AND attempt_no=? ORDER BY round_no,started_at,id
                """, (rs, n) -> new ToolCall(rs.getString("id"), rs.getInt("round_no"), rs.getString("tool_name"),
                rs.getString("status"), rs.getString("arguments_json"), rs.getString("result_text"),
                rs.getString("skill_name"), rs.getString("skill_sha256"), rs.getObject("duration_ms", Long.class),
                rs.getString("error_type"), rs.getLong("started_at")), requestId, attempt);
    }

    public List<Event> events(String requestId) {
        return jdbc.query("""
                SELECT event_type,details,created_at,attempt_no FROM operation_events
                WHERE request_id=? ORDER BY id DESC LIMIT 100
                """, (rs, n) -> new Event(rs.getString("event_type"), rs.getString("details"),
                rs.getLong("created_at"), rs.getObject("attempt_no", Integer.class)), requestId);
    }

    private Conversation conversation(ResultSet rs) throws SQLException {
        return new Conversation(rs.getString("id"), rs.getString("title"), rs.getString("status"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
