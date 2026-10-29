package com.example.ai.service;

import com.example.ai.repository.ChatHistoryRepository;
import com.example.ai.repository.ExecutionAuditRepository;
import com.example.ai.skill.SkillExecutionScope;
import com.example.ai.stream.ChatStream;
import com.example.ai.tool.CustomerTools;
import com.example.ai.tool.ReportTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Enforces every tool's authorization, scope and argument contract before execution. */
@Service
public class ChatToolExecutor {
    private static final Logger log = LoggerFactory.getLogger(ChatToolExecutor.class);
    private final CustomerTools tools;
    private final ReportTools reportTools;
    private final ObjectMapper mapper;
    private final ExecutionAuditRepository audit;

    public ChatToolExecutor(CustomerTools tools, ReportTools reportTools, ObjectMapper mapper, ExecutionAuditRepository audit) {
        this.tools = tools;
        this.reportTools = reportTools;
        this.mapper = mapper;
        this.audit = audit;
    }

    private record ToolResult(String text, boolean success, String skillName, String skillHash) {}

    public String executeRecorded(ChatHistoryRepository.Run run, int round, ToolExecutionRequest call, ChatStream stream, SkillExecutionScope scope) {
        if (stream != null) stream.check();
        String id = UUID.randomUUID().toString();
        audit.startTool(id, run, round, call.id(), call.name(), call.arguments());
        ToolResult result;
        long started = System.currentTimeMillis();
        try {
            log.info("[客服工具执行开始] requestId={} round={} tool={} arguments={}", run.requestId(), round, call.name(), call.arguments());
            emit(stream, "tool_start", Map.of("id", id, "round", round, "name", call.name()));
            result = execute(call,scope);
        }
        catch (RuntimeException error) {
            audit.failTool(id, error instanceof ChatStream.Interrupted ? "INTERRUPTED" : "FAILED", error.getClass().getSimpleName());
            throw error;
        }
        long now = System.currentTimeMillis();
        if (result.success()) {
            log.info("[客服工具执行完成] requestId={} round={} tool={} skill={} resultChars={} durationMs={}",
                    run.requestId(), round, call.name(), result.skillName(), result.text().length(), now - started);
        } else {
            log.warn("[客服工具执行拒绝] requestId={} round={} tool={} reason={}", run.requestId(), round, call.name(), result.text());
        }
        audit.completeTool(id, new ExecutionAuditRepository.ToolResult(
                result.success() ? "COMPLETED" : "FAILED", result.text(), result.skillName(), result.skillHash(),
                result.success() ? null : "ToolInputRejected"));
        emit(stream, "tool_complete", Map.of("id", id, "round", round, "name", call.name(), "status", result.success() ? "COMPLETED" : "FAILED",
                "skillName", result.skillName() == null ? "" : result.skillName(), "durationMs", now - started));
        return result.text();
    }

    private ToolResult execute(ToolExecutionRequest call, SkillExecutionScope scope) {
        try {
            JsonNode args = mapper.readTree(call.arguments());
            if (args == null || !args.isObject()) return new ToolResult("工具参数必须是 JSON 对象", false, null, null);
            if (!Set.of("loadSkill", "findMyOrders", "getMyOrderDetails", "getAccessibleRefundProgress", "getAssignedAfterSalesTicket", "getWarehouseStock", "queryOrderReport").contains(call.name())
                    || !tools.registry().canUseTool(call.name(), tools.identity()) || !scope.allowedTools().contains(call.name()))
                return new ToolResult("未知工具，不允许执行", false, null, null);
            String skill = call.name().equals("loadSkill") ? required(args, "skillName") : null;
            if (skill != null && !tools.registry().canUse(skill, tools.identity()))
                return new ToolResult("技能不存在或无权使用，请使用当前授权目录中的技能", false, null, null);
            if(skill!=null && !scope.candidate(skill)) return new ToolResult("技能不在本轮业务候选范围，请按当前问题选择",false,null,null);
            String objectError=scope.objectError(call.name(),args);
            if(objectError!=null) return new ToolResult(objectError,false,null,null);
            Object result = switch (call.name()) {
                case "loadSkill" -> tools.loadSkill(skill);
                case "findMyOrders" -> tools.findMyOrders(required(args, "productKeyword"));
                case "getMyOrderDetails" -> tools.getMyOrderDetails(required(args, "orderNumber"));
                case "getAccessibleRefundProgress" -> tools.getAccessibleRefundProgress(required(args, "orderNumber"));
                case "getAssignedAfterSalesTicket" -> tools.getAssignedAfterSalesTicket(required(args, "ticketNumber"));
                case "getWarehouseStock" -> tools.getWarehouseStock();
                case "queryOrderReport" -> reportTools.queryOrderReport(required(args, "startDate"), required(args, "endDate"),
                        required(args, "status"), required(args, "productKeyword"), required(args, "groupBy"));
                default -> "未知工具，不允许执行";
            };
            String text = result instanceof String value ? value : mapper.writeValueAsString(result);
            if(skill!=null) scope.select(skill);
            scope.observe(call.name(),result);
            String hash = skill == null ? null : HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
            return new ToolResult(text, true, skill, hash);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() != 400) throw exception;
            return new ToolResult("报表条件无效：" + exception.getReason(), false, null, null);
        } catch (IllegalArgumentException | java.io.IOException exception) {
            return new ToolResult("工具参数无效，请按工具定义提供字符串参数", false, null, null);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String required(JsonNode args, String name) {
        if (!args.path(name).isTextual() || args.path(name).textValue().length() > 100)
            throw new IllegalArgumentException("参数无效");
        return args.path(name).textValue();
    }

    private void emit(ChatStream stream, String name, Object data) {
        if (stream != null) {
            stream.emit(name, data);
        }
    }
}
