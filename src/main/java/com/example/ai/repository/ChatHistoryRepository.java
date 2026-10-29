package com.example.ai.repository;

import com.example.ai.chat.ChatResponse;

import com.example.ai.skill.SkillRouter;
import com.example.ai.stream.ChatStream;
import com.example.ai.security.DemoIdentity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** 每次数据库写入均为短事务；不在模型网络调用期间持有数据库事务。 */
@Repository
@DependsOnDatabaseInitialization
public class ChatHistoryRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final DemoIdentity identity;

    public ChatHistoryRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, ObjectMapper mapper, DemoIdentity identity) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.mapper = mapper;
        this.identity = identity;
    }

    @PostConstruct
    public void recoverInterrupted() {
        tx.executeWithoutResult(ignored -> {
            long now = System.currentTimeMillis();
            recoverEvents(now);
            jdbc.update("UPDATE model_calls SET status='INTERRUPTED', finished_at=?, duration_ms=?-started_at WHERE status='RUNNING'", now, now);
            jdbc.update("UPDATE tool_calls SET status='INTERRUPTED', finished_at=?, duration_ms=?-started_at WHERE status='RUNNING'", now, now);
            jdbc.update("UPDATE chat_runs SET status='INTERRUPTED', error_type='ApplicationRestart', finished_at=?, duration_ms=?-started_at WHERE status='RUNNING'", now, now);
        });
    }

    private record RecoveryRun(String requestId, String conversationId) {}

    private void recoverEvents(long now) {
        String after = null;
        while (true) {
            var batch = recoveryBatch(after);
            if (batch.isEmpty()) {
                return;
            }
            List<Object[]> events = batch.stream().map(run -> new Object[] {
                run.conversationId(), run.requestId(), "RECOVER_INTERRUPTED",
                "上次进程退出时请求未完成，禁止自动重放", now
            }).toList();
            jdbc.batchUpdate("INSERT INTO operation_events(conversation_id,request_id,event_type,details,created_at) VALUES(?,?,?,?,?)", events);
            after = batch.get(batch.size() - 1).requestId();
        }
    }

    private List<RecoveryRun> recoveryBatch(String after) {
        String sql = "SELECT request_id,conversation_id FROM chat_runs WHERE status='RUNNING'"
                + (after == null ? "" : " AND request_id>?") + " ORDER BY request_id LIMIT 500";
        Object[] parameters = after == null ? new Object[0] : new Object[] {after};
        return jdbc.query(sql, (rs, n) -> new RecoveryRun(rs.getString("request_id"), rs.getString("conversation_id")), parameters);
    }

    public record Run(String requestId, String conversationId, String input, String status, int attempt,
                      String responseJson) {}

    public Optional<Run> find(String requestId) {
        return jdbc.query("SELECT request_id,conversation_id,input_text,status,attempt_count,response_json FROM chat_runs WHERE request_id=?", (rs, n) -> new Run(
                rs.getString("request_id"), rs.getString("conversation_id"), rs.getString("input_text"),
                rs.getString("status"), rs.getInt("attempt_count"), rs.getString("response_json")), requestId).stream().findFirst();
    }

    public Run begin(String requestId, String requestedConversation, String message, String source) {
        return tx.execute(ignored -> {
            Optional<Run> previous = find(requestId);
            if (previous.isPresent()) {
                Run run = previous.get();
                String oldSource = jdbc.queryForObject("SELECT source FROM conversations WHERE id=?", String.class, run.conversationId());
                String owner = jdbc.queryForObject("SELECT owner_id FROM conversations WHERE id=?", String.class, run.conversationId());
                if (!identity.ownerId().equals(owner) || !run.input().equals(message) || (requestedConversation != null && !requestedConversation.equals(run.conversationId())) || !source.equals(oldSource))
                    throw conflict("同一 requestId 不能用于不同消息、会话或接口");
                if (run.status().equals("COMPLETED")) {
                    event(run.conversationId(), requestId, "REPLAY_COMPLETED", "返回已保存结果，未重复调用模型");
                    return run;
                }
                if (!run.status().equals("FAILED")) throw conflict("请求正在执行或已中断；请确认记录后使用新的 requestId");
                requireActive(run.conversationId());
                long now = System.currentTimeMillis();
                jdbc.update("UPDATE chat_runs SET status='RUNNING', attempt_count=attempt_count+1, started_at=?, finished_at=NULL, duration_ms=NULL, error_type=NULL WHERE request_id=?", now, requestId);
                event(run.conversationId(), requestId, "RETRY", "用户显式重试失败请求");
                return new Run(requestId, run.conversationId(), message, "RUNNING", run.attempt() + 1, null);
            }
            String conversation = requestedConversation == null ? UUID.randomUUID().toString() : requestedConversation;
            var owners = jdbc.queryForList("SELECT owner_id FROM conversations WHERE id=?", String.class, conversation);
            long now = System.currentTimeMillis();
            boolean created = owners.isEmpty();
            if (created) {
                jdbc.update("INSERT INTO conversations(id,owner_id,title,source,created_at,updated_at) VALUES(?,?,?,?,?,?)",
                        conversation, identity.ownerId(), message.substring(0, Math.min(100, message.length())), source, now, now);
            } else {
                String existingSource = jdbc.queryForObject("SELECT source FROM conversations WHERE id=?", String.class, conversation);
                if (!owners.get(0).equals(identity.ownerId()) || !source.equals(existingSource)) throw conflict("无法访问此会话");
            }
            requireActive(conversation);
            jdbc.update("INSERT INTO chat_runs(request_id,conversation_id,input_text,status,attempt_count,created_at,started_at) VALUES(?,?,?,'RUNNING',1,?,?)",
                    requestId, conversation, message, now, now);
            jdbc.update("INSERT INTO chat_messages(request_id,role,content,created_at) VALUES(?,'USER',?,?)", requestId, message, now);
            if (created) event(conversation, requestId, "NEW_CONVERSATION", source);
            event(conversation, requestId, "SEND_MESSAGE", "请求开始");
            jdbc.update("UPDATE conversations SET updated_at=? WHERE id=?", now, conversation);
            return new Run(requestId, conversation, message, "RUNNING", 1, null);
        });
    }

    public List<ChatMessage> recentContext(String conversation) {
        // 按成功完成顺序而非首次提交顺序：早先失败的请求可能在后续消息之后才被重试成功。
        List<String> turns = jdbc.queryForList("SELECT r.context_json FROM chat_runs r JOIN chat_messages m ON m.request_id=r.request_id AND m.role='ASSISTANT' WHERE r.conversation_id=? AND r.status='COMPLETED' AND r.context_json IS NOT NULL ORDER BY m.id DESC LIMIT 6", String.class, conversation);
        Collections.reverse(turns);
        List<ChatMessage> messages = new ArrayList<>();
        turns.forEach(json -> messages.addAll(ChatMessageDeserializer.messagesFromJson(json)));
        return messages;
    }

    public void complete(Run run, ChatResponse response, List<ChatMessage> context) {
        complete(run,response,context,null);
    }

    public SkillRouter.State businessState(String conversation) {
        var rows=jdbc.queryForList("SELECT r.business_state_json FROM chat_runs r JOIN conversations c ON c.id=r.conversation_id JOIN chat_messages m ON m.request_id=r.request_id AND m.role='ASSISTANT' WHERE c.id=? AND c.owner_id=? AND r.status='COMPLETED' AND r.business_state_json IS NOT NULL ORDER BY m.id DESC LIMIT 1",String.class,conversation,identity.ownerId());
        if(rows.isEmpty()) return SkillRouter.State.empty();
        try {return mapper.readValue(rows.get(0),SkillRouter.State.class);}
        catch(JsonProcessingException error) {throw new IllegalStateException("业务状态记录无效",error);}
    }

    public void complete(Run run, ChatResponse response, List<ChatMessage> context, SkillRouter.State state) {
        String responseJson = json(response);
        String contextJson = ChatMessageSerializer.messagesToJson(context);
        tx.executeWithoutResult(ignored -> {
            long now = System.currentTimeMillis();
            jdbc.update("INSERT INTO chat_messages(request_id,role,content,created_at) VALUES(?,'ASSISTANT',?,?)", run.requestId(), response.answer(), now);
            jdbc.update("UPDATE chat_runs SET status='COMPLETED',response_json=?,context_json=?,finished_at=?,duration_ms=?-started_at WHERE request_id=? AND status='RUNNING'",
                    responseJson, contextJson, now, now, run.requestId());
            jdbc.update("UPDATE conversations SET updated_at=? WHERE id=?", now, run.conversationId());
            if(state!=null) jdbc.update("UPDATE chat_runs SET business_state_json=? WHERE request_id=?",json(state),run.requestId());
            event(run.conversationId(), run.requestId(), "COMPLETED", "回答已保存");
        });
    }

    public void fail(Run run, RuntimeException error) {
        tx.executeWithoutResult(ignored -> {
            long now = System.currentTimeMillis();
            // 数据库故障时不能保证最后一步是否提交，按不确定状态处理，避免自动重放。
            String status = error instanceof org.springframework.dao.DataAccessException || error instanceof ChatStream.Interrupted ? "INTERRUPTED" : "FAILED";
            jdbc.update("UPDATE chat_runs SET status=?,error_type=?,finished_at=?,duration_ms=?-started_at WHERE request_id=? AND status='RUNNING'",
                    status, error.getClass().getSimpleName(), now, now, run.requestId());
            event(run.conversationId(), run.requestId(), status, error.getClass().getSimpleName());
        });
    }

    public ChatResponse savedResponse(Run run) {
        try { return mapper.readValue(run.responseJson(), ChatResponse.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("保存的响应格式错误", e); }
    }

    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("记录序列化失败", e); }
    }

    private void event(String conversation, String request, String type, String detail) {
        jdbc.update("INSERT INTO operation_events(conversation_id,request_id,event_type,details,created_at) VALUES(?,?,?,?,?)",
                conversation, request, type, detail, System.currentTimeMillis());
    }

    private ResponseStatusException conflict(String reason) { return new ResponseStatusException(HttpStatus.CONFLICT, reason); }

    private void requireActive(String id) {
        String status = jdbc.queryForObject("SELECT status FROM conversations WHERE id=? FOR UPDATE", String.class, id);
        if (!"ACTIVE".equals(status)) throw conflict("会话已归档，请恢复后再发送消息");
    }
}
