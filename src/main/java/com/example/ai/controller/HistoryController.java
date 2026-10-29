package com.example.ai.controller;

import com.example.ai.history.HistoryModels;
import com.example.ai.history.HistoryModels.Change;

import com.example.ai.service.HistoryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Tag(name = "历史记录", description = "当前后端演示身份的会话、消息和执行记录；查询不会调用模型")
public class HistoryController {
    private final HistoryService history;

    public HistoryController(HistoryService history) { this.history = history; }

    @GetMapping("/conversations")
    @Operation(summary = "分页查询会话列表")
    public HistoryModels.ConversationPage conversations(@RequestParam(defaultValue = "ACTIVE") String status,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        return history.conversations(status, page, size);
    }

    @GetMapping("/conversations/{id}/messages")
    @Operation(summary = "查询会话消息", description = "默认最近 30 条，按时间正序返回；加载更早记录时使用 nextBeforeId。归档会话也可读取。")
    public HistoryModels.MessagePage messages(@PathVariable String id, @RequestParam(required = false) Long beforeId,
                                               @RequestParam(defaultValue = "30") int size) {
        return history.messages(id, beforeId, size);
    }

    @PatchMapping("/conversations/{id}")
    @Operation(summary = "重命名或归档会话", description = "status 支持 ACTIVE、ARCHIVED。归档仅隐藏，不删除任何记录；正在处理请求时拒绝归档。")
    public HistoryModels.Conversation change(@PathVariable String id, @Valid @RequestBody Change request) {
        return history.change(id, request);
    }

    @GetMapping("/chat-runs/{id}")
    @Operation(summary = "查询一次请求的执行详情", description = "默认展示最近一次尝试；attempt 可选择历史尝试。包含模型、工具、技能及最近 100 条操作事件。")
    public HistoryModels.RunDetail run(@PathVariable String id, @RequestParam(required = false) Integer attempt) {
        return history.run(id, attempt);
    }

}
