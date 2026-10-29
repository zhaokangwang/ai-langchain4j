package com.example.ai.service;

import com.example.ai.chat.ChatResponse;

import com.example.ai.aftersales.AfterSalesModels.ReviewRequest;

import com.example.ai.model.RecordedModelClient;
import com.example.ai.security.DemoIdentity;
import com.example.ai.repository.ChatHistoryRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class AfterSalesService {
    public static final String SKILL_NAME = "order-after-sales-review";
    private static final Logger log = LoggerFactory.getLogger(AfterSalesService.class);
    private final RecordedModelClient chatModel;
    private final ChatHistoryRepository history;
    private final ObjectMapper objectMapper;
    private final String instructions;
    private final DemoIdentity identity;

    public AfterSalesService(RecordedModelClient chatModel, ObjectMapper objectMapper, ChatHistoryRepository history, DemoIdentity identity) throws IOException {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.history = history;
        this.identity = identity;
        // 从 classpath 读取，打包为 jar 后也可用；规则在启动时加载。
        var resource = new ClassPathResource("skills/" + SKILL_NAME + "/SKILL.md");
        try (var input = resource.getInputStream()) {
            instructions = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (instructions.isBlank()) {
            throw new IllegalStateException("售后技能文件不能为空");
        }
    }

    public String review(ReviewRequest request) throws JsonProcessingException {
        if (identity.current().role()==DemoIdentity.Role.WAREHOUSE)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"当前演示身份无售后咨询权限");
        log.info("加载的文件技能用于本次咨询：{}", SKILL_NAME);
        // 规则放入 SystemMessage；外部事实作为 JSON 放入 UserMessage，不拼进系统指令。
        var modelRequest = ChatRequest.builder()
                .messages(SystemMessage.from(instructions), UserMessage.from(objectMapper.writeValueAsString(request)))
                .build();
        String callId = UUID.randomUUID().toString();
        var run = history.begin(callId, null, objectMapper.writeValueAsString(request), "AFTER_SALES:" + SKILL_NAME);
        long started = System.nanoTime();
        log.info("[大模型调用开始] callId={} skill={}", callId, SKILL_NAME);
        try {
            var response = chatModel.chat(run, 1, modelRequest);
            String answer = response.aiMessage().text();
            if (answer == null || answer.isBlank()) throw new IllegalStateException("模型回答为空");
            log.info("[大模型调用成功] callId={} model={} durationMs={}\n[模型返回正文开始]\n{}\n[模型返回正文结束]",
                    callId, response.metadata().modelName(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), answer);
            var result = new ChatResponse(answer, run.conversationId(), java.util.List.of(), callId);
            history.complete(run, result, java.util.List.of(modelRequest.messages().get(1), response.aiMessage()));
            return answer;
        } catch (RuntimeException exception) {
            log.error("[大模型调用失败] callId={} durationMs={} errorType={}", callId,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), exception.getClass().getSimpleName());
            try { history.fail(run, exception); }
            catch (RuntimeException recordingFailure) { exception.addSuppressed(recordingFailure); }
            throw exception;
        }
    }
}
