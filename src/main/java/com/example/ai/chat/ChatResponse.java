package com.example.ai.chat;

import io.swagger.v3.oas.annotations.media.Schema;

public record ChatResponse(
            @Schema(description = "模型生成的回答", example = "依赖注入是由 Spring 容器为对象提供其所需依赖……")
            String answer,
            String conversationId,
            @Schema(description = "本轮真实执行过的工具名称，便于学习观察") java.util.List<String> executedTools,
            String requestId) {}
