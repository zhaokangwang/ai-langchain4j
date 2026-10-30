package com.example.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** 不使用 MockMvc、模拟登录或 Repository 夹具，从真实 TCP/HTTP 入口完成整条账号工作流。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"langchain4j.open-ai.chat-model.api-key=local-workflow-not-used", "server.address=127.0.0.1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountWorkflowHttpIntegrationTest {
    static final WorkflowModelStub upstream = WorkflowModelStub.start();
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry properties) {
        properties.add("langchain4j.open-ai.chat-model.base-url", upstream::baseUrl);
        properties.add("langchain4j.open-ai.streaming-chat-model.base-url", upstream::baseUrl);
    }

    @AfterAll static void stopProvider() { upstream.close(); }

    private record Reply(int status, HttpHeaders headers, byte[] bytes) {
        String body() { return new String(bytes, StandardCharsets.UTF_8); }
    }

    private record Account(String id, String username, String role, WireClient client) { }

    private final class WireClient {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5)).build();

        Reply get(String path) throws Exception { return send("GET", path, null, null); }

        Reply write(String method, String path, Object body) throws Exception {
            Reply response = get("/api/auth/csrf");
            assertThat(response.status()).isEqualTo(200);
            return send(method, path, body, json(response).path("token").asText());
        }

        Reply send(String method, String path, Object body, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
            if (token != null) request.header("X-CSRF-TOKEN", token);
            if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
            else request.header("Content-Type", "application/json").method(method,
                    HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.headers(), response.body());
        }

        Reply login(String username, String password) throws Exception {
            return write("POST", "/api/auth/login", Map.of("username", username, "password", password));
        }

        String cookieId() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                    .map(java.net.HttpCookie::getValue).findFirst().orElse(null);
        }
    }

    private JsonNode json(Reply response) throws Exception { return mapper.readTree(response.bytes()); }
    private String password(String username) { return "changed-" + username; }

    @Test
    void shouldCompleteAccountRoleChatReportAndRevocationWorkflowOverRealHttp() throws Exception {
        var admin = initializeAdministrator();
        var people = new LinkedHashMap<String, Account>();
        for (String role : new String[]{"buyer", "service", "warehouse"}) {
            people.put(role, createAndActivate(admin, role + "-e2e", role));
        }
        var peer = createAndActivate(admin, "buyer-peer-e2e", "buyer");
        verifyManagementAndPermissions(admin, people);
        String conversation = verifyChatAndReplay(people, peer);
        verifyStreaming(people.get("service"));
        String report = verifyReports(people, peer);
        verifyDisableResetAndLogout(admin, people.get("buyer"), peer, conversation, report);
        verifyOpenApi(admin);
        assertThat(upstream.requests()).isEqualTo(2);
    }

    private WireClient initializeAdministrator() throws Exception {
        var admin = new WireClient();
        Reply root = admin.get("/");
        assertThat(root.status()).isEqualTo(302);
        assertThat(root.headers().firstValue("location").orElseThrow()).contains("/login.html");
        assertThat(admin.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(admin.get("/login.html").body()).contains("登录你的账号");
        Reply csrf = admin.get("/api/auth/csrf");
        assertThat(csrf.headers().allValues("set-cookie").stream().anyMatch(header -> {
            String lower = header.toLowerCase(java.util.Locale.ROOT);
            return lower.contains("httponly") && lower.contains("samesite=lax");
        })).isTrue();
        assertThat(admin.send("POST", "/api/auth/login", Map.of("username", "admin", "password", "admin"), null).status()).isEqualTo(403);
        String anonymousId = admin.cookieId();
        Reply login = admin.login("admin", "admin");
        assertThat(login.status()).isEqualTo(200);
        assertThat(admin.cookieId()).isNotNull().isNotEqualTo(anonymousId);
        assertThat(json(login).path("passwordChangeRequired").asBoolean()).isTrue();
        assertThat(admin.get("/api/admin/accounts").status()).isEqualTo(403);
        assertThat(admin.write("POST", "/api/auth/password", Map.of("currentPassword", "admin", "newPassword", password("admin"))).status()).isEqualTo(204);
        assertThat(admin.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(admin.login("admin", password("admin")).status()).isEqualTo(200);
        assertThat(admin.get("/admin-accounts.html").status()).isEqualTo(200);
        return admin;
    }

    private Account createAndActivate(WireClient admin, String username, String role) throws Exception {
        Reply created = admin.write("POST", "/api/admin/accounts", Map.of("username", username, "password", "initial-password", "role", role));
        assertThat(created.status()).isEqualTo(201);
        var row = json(created);
        assertThat(row.path("administrator").asBoolean()).isFalse();
        assertThat(row.has("passwordHash")).isFalse();
        var client = new WireClient();
        assertThat(json(client.login(username, "initial-password")).path("passwordChangeRequired").asBoolean()).isTrue();
        assertThat(client.write("POST", "/api/auth/password", Map.of("currentPassword", "initial-password", "newPassword", password(username))).status()).isEqualTo(204);
        assertThat(client.login(username, password(username)).status()).isEqualTo(200);
        var capabilities = json(client.get("/api/demo/capabilities"));
        assertThat(capabilities.path("actor").path("username").asText()).isEqualTo(username);
        assertThat(capabilities.path("skills").size()).isEqualTo(role.equals("buyer") ? 6 : role.equals("service") ? 8 : 3);
        return new Account(row.path("id").asText(), username, role, client);
    }

    private void verifyManagementAndPermissions(WireClient admin, Map<String, Account> people) throws Exception {
        Reply first = admin.get("/api/admin/accounts?keyword=e2e&role=buyer&page=0&size=1");
        assertThat(first.status()).isEqualTo(200);
        assertThat(json(first).path("items").size()).isEqualTo(1);
        assertThat(json(first).path("hasMore").asBoolean()).isTrue();
        assertThat(json(admin.get("/api/admin/accounts?keyword=e2e&role=buyer&page=1&size=1")).path("hasMore").asBoolean()).isFalse();
        var warehouse = people.get("warehouse").client();
        assertThat(warehouse.get("/admin-accounts.html").status()).isEqualTo(403);
        assertThat(warehouse.get("/api/admin/accounts").status()).isEqualTo(403);
        assertThat(warehouse.write("POST", "/api/admin/accounts", Map.of("username", "forbidden-e2e", "password", "initial-password", "role", "buyer")).status()).isEqualTo(403);
        assertThat(admin.write("POST", "/api/admin/accounts", Map.of("username", "buyer-e2e", "password", "initial-password", "role", "buyer")).status()).isEqualTo(409);
        String ownId = json(admin.get("/api/auth/me")).path("id").asText();
        assertThat(admin.write("PATCH", "/api/admin/accounts/" + ownId + "/status", Map.of("enabled", false)).status()).isEqualTo(409);
    }

    private String verifyChatAndReplay(Map<String, Account> people, Account peer) throws Exception {
        String request = UUID.randomUUID().toString();
        var message = Map.of("message", "解释 Java record", "requestId", request);
        Reply response = people.get("buyer").client().write("POST", "/api/chat", message);
        assertThat(response.status()).isEqualTo(200);
        assertThat(json(response).path("answer").asText()).isEqualTo("本地模型联调成功");
        String conversation = json(response).path("conversationId").asText();
        assertThat(peer.client().get("/api/conversations/" + conversation + "/messages").status()).isEqualTo(200);
        assertThat(json(peer.client().write("POST", "/api/chat", message))).isEqualTo(json(response));
        assertThat(people.get("warehouse").client().get("/api/conversations/" + conversation + "/messages").status()).isEqualTo(404);
        assertThat(people.get("warehouse").client().write("POST", "/api/chat", message).status()).isEqualTo(409);
        assertThat(upstream.requests()).isEqualTo(1);
        var events = json(peer.client().get("/api/chat-runs/" + request)).path("events");
        boolean foundActor = false;
        for (var event : events) {
            if (event.path("type").asText().equals("AUTHORIZATION")) {
                assertThat(mapper.readTree(event.path("details").asText()).path("actor").path("id").asText()).isEqualTo(people.get("buyer").id());
                foundActor = true;
            }
        }
        assertThat(foundActor).isTrue();
        return conversation;
    }

    private void verifyStreaming(Account staff) throws Exception {
        String request = UUID.randomUUID().toString();
        Reply response = staff.client().write("POST", "/api/chat/stream", Map.of("message", "解释 Java record", "requestId", request));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElseThrow()).contains("text/event-stream");
        assertThat(response.body()).contains("event:authorization", "CUSTOMER_SERVICE", staff.username(), "event:delta", "event:done", "本地模型联调成功")
                .doesNotContain("event:error");
        assertThat(staff.client().get("/api/chat-runs/" + request).status()).isEqualTo(200);
    }

    private String verifyReports(Map<String, Account> people, Account peer) throws Exception {
        String buyerReport = null;
        var filter = Map.of("startDate", "2026-10-01", "endDate", "2026-10-31", "status", "", "productKeyword", "", "groupBy", "PRODUCT");
        for (var account : people.values()) {
            Reply created = account.client().write("POST", "/api/reports/orders", filter);
            assertThat(created.status()).isEqualTo(200);
            int count = account.role().equals("buyer") ? 3 : 1;
            assertThat(json(created).path("summary").path("orderCount").asInt()).isEqualTo(count);
            String reportId = json(created).path("reportId").asText();
            Reply download = account.client().get("/api/reports/orders/" + reportId + "/download");
            assertThat(download.status()).isEqualTo(200);
            assertThat(download.headers().firstValue("content-type").orElseThrow()).contains("spreadsheetml.sheet");
            assertThat(download.headers().firstValue("content-disposition").orElseThrow()).contains("attachment", ".xlsx");
            try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(download.bytes()))) {
                assertThat(workbook.getSheet("订单明细").getLastRowNum()).isEqualTo(count);
                assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            }
            if (account.role().equals("buyer")) buyerReport = reportId;
        }
        assertThat(peer.client().get("/api/reports/orders/" + buyerReport).status()).isEqualTo(200);
        assertThat(peer.client().get("/api/reports/orders/" + buyerReport + "/download").status()).isEqualTo(200);
        assertThat(people.get("warehouse").client().get("/api/reports/orders/" + buyerReport + "/download").status()).isEqualTo(404);
        return buyerReport;
    }

    private void verifyDisableResetAndLogout(WireClient admin, Account buyer, Account peer, String conversation, String report) throws Exception {
        var unusedOldLogin = new WireClient();
        assertThat(unusedOldLogin.login(buyer.username(), password(buyer.username())).status()).isEqualTo(200);
        String path = "/api/admin/accounts/" + buyer.id();
        assertThat(admin.write("PATCH", path + "/status", Map.of("enabled", false)).status()).isEqualTo(200);
        assertThat(buyer.client().get("/api/auth/me").status()).isEqualTo(401);
        assertThat(buyer.client().login(buyer.username(), password(buyer.username())).status()).isEqualTo(401);
        assertThat(peer.client().get("/api/conversations/" + conversation + "/messages").status()).isEqualTo(200);
        assertThat(admin.write("PATCH", path + "/status", Map.of("enabled", true)).status()).isEqualTo(200);
        assertThat(unusedOldLogin.get("/api/auth/me").status()).isEqualTo(401);
        assertThat(buyer.client().login(buyer.username(), password(buyer.username())).status()).isEqualTo(200);
        assertThat(admin.write("POST", path + "/password", Map.of("newPassword", "reset-e2e-password")).status()).isEqualTo(204);
        assertThat(buyer.client().get("/api/auth/me").status()).isEqualTo(401);
        assertThat(buyer.client().login(buyer.username(), password(buyer.username())).status()).isEqualTo(401);
        assertThat(json(buyer.client().login(buyer.username(), "reset-e2e-password")).path("passwordChangeRequired").asBoolean()).isTrue();
        assertThat(buyer.client().write("POST", "/api/auth/password", Map.of("currentPassword", "reset-e2e-password", "newPassword", "final-e2e-password")).status()).isEqualTo(204);
        assertThat(buyer.client().login(buyer.username(), "final-e2e-password").status()).isEqualTo(200);
        assertThat(buyer.client().get("/api/reports/orders/" + report).status()).isEqualTo(200);
        assertThat(buyer.client().write("POST", "/api/auth/logout", Map.of()).status()).isEqualTo(204);
        assertThat(buyer.client().get("/api/auth/me").status()).isEqualTo(401);
        assertThat(peer.client().get("/api/auth/me").status()).isEqualTo(200);
    }

    private void verifyOpenApi(WireClient admin) throws Exception {
        Reply response = admin.get("/v3/api-docs");
        assertThat(response.status()).isEqualTo(200);
        var document = json(response);
        assertThat(document.at("/components/securitySchemes/sessionCookie/name").asText()).isEqualTo("JSESSIONID");
        assertThat(document.at("/components/securitySchemes/csrfToken/name").asText()).isEqualTo("X-CSRF-TOKEN");
        var paths = document.path("paths");
        assertThat(paths.path("/api/admin/accounts").path("post").path("responses").has("201")).isTrue();
        assertThat(paths.path("/api/admin/accounts/{id}/password").path("post").path("responses").has("204")).isTrue();
        assertThat(paths.path("/api/auth/logout").path("post").path("responses").has("204")).isTrue();
        assertThat(paths.path("/api/auth/password").path("post").path("responses").has("204")).isTrue();
    }
}
