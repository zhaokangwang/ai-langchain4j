package com.example.ai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅监听回环地址的 OpenAI 协议模拟供应商，实际应用仍使用原自动配置的模型客户端。 */
final class WorkflowModelStub implements AutoCloseable {
    private final HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    private WorkflowModelStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.matches("(?s).*\"stream\"\\s*:\\s*true.*")) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, 0);
                try (var output = exchange.getResponseBody()) {
                    for (String text : new String[]{"本地模型", "联调成功"}) {
                        String event = "data: {\"id\":\"workflow\",\"object\":\"chat.completion.chunk\",\"model\":\"local-workflow\","
                                + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}\n\n";
                        output.write(event.getBytes(StandardCharsets.UTF_8));
                        output.flush();
                    }
                    output.write(("data: {\"id\":\"workflow\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                }
            } else {
                byte[] response = """
                        {"id":"workflow","object":"chat.completion","created":0,"model":"local-workflow",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"本地模型联调成功"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
            }
        });
        server.start();
    }

    static WorkflowModelStub start() {
        try { return new WorkflowModelStub(); }
        catch (IOException error) { throw new ExceptionInInitializerError(error); }
    }

    String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    int requests() { return requests.get(); }
    @Override public void close() { server.stop(0); }
}
