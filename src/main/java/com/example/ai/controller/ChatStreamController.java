package com.example.ai.controller;

import com.example.ai.chat.ChatRequest;

import com.example.ai.stream.ChatStream;
import com.example.ai.service.CustomerChatService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/chat/stream")
@Tag(name = "实时聊天", description = "新增 SSE 接口；原 POST /api/chat 保持 JSON 行为。事件不是模型内部思考过程。")
public class ChatStreamController {
    private final CustomerChatService service;
    private final ConcurrentMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 4, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), runnable -> { var thread = new Thread(runnable, "chat-sse-worker"); thread.setDaemon(true); return thread; });
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "chat-sse-heartbeat"); thread.setDaemon(true); return thread;
    });

    public ChatStreamController(CustomerChatService service) { this.service = service; }

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "实时聊天（SSE）", description = "POST 相同消息结构，返回 accepted、model_start、delta、model_complete、tool_start、tool_complete、done 或 error。done 表示已持久化。")
    public SseEmitter stream(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        String requestId = request.requestId() == null ? UUID.randomUUID().toString() : request.requestId();
        var connection = new Connection(service.sharedOwnerId());
        var execution = service.prepareChat(request.conversationId(), request.message(), requestId, connection.control);
        if (connections.putIfAbsent(requestId, connection) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "请求正在实时处理中");
        var emitter = connection.emitter;
        emitter.onError(error -> connection.control.cancel("连接已断开"));
        emitter.onTimeout(() -> connection.control.cancel("实时连接超时"));
        emitter.onCompletion(() -> { if (!connection.finished.get()) connection.control.cancel("连接已结束"); });
        try {
            executor.execute(() -> {
                ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
                    try { connection.send("heartbeat", Map.of("time", System.currentTimeMillis())); }
                    catch (RuntimeException error) { connection.control.cancel("心跳写入失败，连接已断开"); }
                }, 15, 15, TimeUnit.SECONDS);
                try {
                    connection.control.check();
                    var result = execution.get();
                    connection.send("done", result);
                } catch (RuntimeException error) {
                    int status = error instanceof ResponseStatusException http ? http.getStatusCode().value() : error instanceof ChatStream.Interrupted ? 499 : 502;
                    String message = error instanceof ResponseStatusException http ? http.getReason() : error instanceof ChatStream.Interrupted ? "生成已停止或连接中断；请查看执行记录。" : "模型处理失败，请查看执行记录或后端日志。";
                    try { connection.send("error", Map.of("requestId", requestId, "status", status, "message", message == null ? "请求失败" : message)); }
                    catch (RuntimeException ignored) { /* 客户端可能已断开，数据库状态由执行服务保存 */ }
                } finally {
                    heartbeat.cancel(false); connection.finished.set(true); connections.remove(requestId, connection); emitter.complete();
                }
            });
        } catch (RejectedExecutionException error) {
            connections.remove(requestId, connection);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "实时处理队列已满，请稍后再试");
        }
        return emitter;
    }

    @PostMapping("/{requestId}/cancel")
    @Operation(summary = "停止当前角色共享范围内的一次实时生成", description = "同角色账号可取消；跨角色与不存在均返回 false。尽力取消上游，不撤销已完成的操作或模型费用。")
    public Map<String, Object> cancel(@PathVariable String requestId) {
        var connection = connections.get(requestId);
        String owner = service.sharedOwnerId();
        if (connection == null || !connection.owner.equals(owner)) return Map.of("requestId", requestId, "cancelRequested", false);
        connection.control.cancel("用户停止生成");
        return Map.of("requestId", requestId, "cancelRequested", true);
    }

    @PreDestroy
    public void shutdown() {
        connections.values().forEach(connection -> connection.control.cancel("应用正在停止"));
        executor.shutdown(); heartbeats.shutdown();
    }

    private static class Connection {
        final String owner;
        Connection(String owner) { this.owner = owner; }
        final SseEmitter emitter = new SseEmitter(600_000L);
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicLong sequence = new AtomicLong();
        final ChatStream control = new ChatStream(this::send);
        synchronized void send(String name, Object data) {
            try { emitter.send(SseEmitter.event().id(Long.toString(sequence.incrementAndGet())).name(name).data(data, MediaType.APPLICATION_JSON)); }
            catch (IOException | IllegalStateException error) { control.cancel("连接已断开"); throw new ChatStream.Interrupted("SSE 写入失败"); }
        }
    }
}
