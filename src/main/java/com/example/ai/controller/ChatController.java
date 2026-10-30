package com.example.ai.controller;

import com.example.ai.service.CustomerChatService;
import com.example.ai.chat.ChatRequest;
import com.example.ai.chat.ChatResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "AI 聊天", description = "通过 LangChain4j 调用 DeepSeek，支持普通问答及模拟订单查询工具")
@RequestMapping("/api/chat")
public class ChatController {
    private final CustomerChatService service;

    public ChatController(CustomerChatService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "向 AI 提问", description = "按登录角色授权技能、工具及数据，同角色共享会话。首轮可省略 conversationId，后续传回响应中的 ID。全部为模拟业务，无真实退款操作。")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return service.chat(request.conversationId(), request.message(), request.requestId());
    }

}
