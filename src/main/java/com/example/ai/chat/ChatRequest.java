package com.example.ai.chat;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
            @Schema(description = "问题内容，不能为空，最多 4000 个字符", example = "请用一个简单例子解释 Spring Boot 的依赖注入")
            @NotBlank @Size(max = 4000) String message,
            @Schema(description = "首轮省略，后续使用上次响应返回的会话 ID")
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String conversationId,
            @Schema(description = "客户端为每条新消息生成；重试同一消息保持不变，用于避免重复模型调用")
            @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String requestId) {}
