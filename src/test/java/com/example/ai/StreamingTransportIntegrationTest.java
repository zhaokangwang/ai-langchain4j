package com.example.ai;

import org.springframework.context.annotation.Import;

import com.example.ai.repository.ChatHistoryRepository;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用真实自动配置的 OpenAI 模型和 Spring RestClient，仅供应商服务为本地模拟。 */
@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=local-test-only")
@AutoConfigureMockMvc
@Import(TestAuthenticatedRequests.class)
@WithBusinessIdentity
class StreamingTransportIntegrationTest {
    static final AtomicInteger requests = new AtomicInteger();
    static final HttpServer upstream = startServer();
    @Autowired MockMvc mvc;
    @Autowired ChatHistoryRepository history;

    static HttpServer startServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                requests.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    for (String text : new String[]{"你好", "，传输层测试成功。"}) {
                        String frame = "data: {\"id\":\"local\",\"object\":\"chat.completion.chunk\",\"model\":\"local-test\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}\n\n";
                        output.write(frame.getBytes(StandardCharsets.UTF_8)); output.flush();
                    }
                    output.write(("data: {\"id\":\"local\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                }
            });
            server.start(); return server;
        } catch (Exception error) { throw new ExceptionInInitializerError(error); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("langchain4j.open-ai.chat-model.base-url", () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }
    @AfterAll static void stop() { upstream.stop(0); }

    @Test void shouldStreamUsingActualSpringRestClientWithoutReactiveTransportSupport() throws Exception {
        String id = UUID.randomUUID().toString();
        var result = mvc.perform(post("/api/chat/stream").contentType("application/json")
                .content("{\"message\":\"传输测试\",\"requestId\":\"" + id + "\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10_000);
        String body = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:delta", "你好", "传输层测试成功", "event:done").doesNotContain("event:error");
        assertThat(history.find(id).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(requests.get()).isEqualTo(1);
    }
}
