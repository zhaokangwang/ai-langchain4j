package com.example.ai;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 只替换模型边界：Controller、AI Service 代理及提示词组装都是真实实现。
@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@AutoConfigureMockMvc
class ChatIntegrationTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    ChatModel chatModel;

    @Test
    void shouldServeLocalVueChatPageAndAssetsWithoutCallingModel() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("vendor/vue.global.prod.js")));
        for (String asset : java.util.List.of("/vendor/vue.global.prod.js", "/vendor/marked.umd.js", "/vendor/purify.min.js",
                "/css/chat.css", "/css/content.css", "/css/insights.css", "/js/api.js", "/js/content.js", "/js/insights.js", "/js/chat.js")) {
            mvc.perform(get(asset)).andExpect(status().isOk());
        }
        verifyNoInteractions(chatModel);
    }

    @Test
    void shouldExecuteOrderToolAndSendActualResultBackToModel() throws Exception {
        verifyOrderToolFlow("A1001", "已签收", "SF-DEMO-1001");
    }

    @Test
    void shouldReturnNotFoundToolResultForUnknownOrder() throws Exception {
        mvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"查询订单 A9999\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("未找到可访问的订单")));
        verifyNoInteractions(chatModel);
    }

    @Test
    void shouldValidateOrderNumberInsideTool() throws Exception {
        mvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"查询订单 B2001\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.containsString("未找到可访问的订单")));
        verifyNoInteractions(chatModel);
    }

    private void verifyOrderToolFlow(String orderNumber, String expectedResult, String expectedDetail) throws Exception {
        ToolExecutionRequest toolCall = ToolExecutionRequest.builder()
                .id("order-call-1").name("getMyOrderDetails")
                .arguments("{\"orderNumber\":\"" + orderNumber + "\"}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("skill").name("loadSkill").arguments("{\"skillName\":\"order-query\"}").build())).build(),
                ChatResponse.builder().aiMessage(AiMessage.from(toolCall)).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("模拟订单查询完成")).build());

        mvc.perform(post("/api/chat").contentType("application/json")
                        .content("{\"message\":\"查询订单 " + orderNumber + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("模拟订单查询完成"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel, times(3)).chat(captor.capture());
        assertThat(captor.getAllValues().get(1).toolSpecifications())
                .anySatisfy(tool -> assertThat(tool.name()).isEqualTo("getMyOrderDetails"));
        ToolExecutionResultMessage result = captor.getAllValues().get(2).messages().stream()
                .filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).filter(m->m.toolName().equals("getMyOrderDetails")).findFirst().orElseThrow();
        assertThat(result.toolName()).isEqualTo("getMyOrderDetails");
        assertThat(result.id()).isEqualTo("order-call-1");
        assertThat(result.text()).contains(expectedResult, expectedDetail);
    }

    @Test
    void shouldServeSwaggerUiAndChatDocumentationWithoutCallingModel() throws Exception {
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("LangChain4j 学习接口"))
                .andExpect(jsonPath("$.paths['/api/chat'].post.summary").value("向 AI 提问"))
                .andExpect(jsonPath("$.components.schemas.ChatRequest.properties.message.example")
                        .value("请用一个简单例子解释 Spring Boot 的依赖注入"));
        verifyNoInteractions(chatModel);
    }

    @Test
    void shouldCallAiServiceWithSystemAndUserMessages() throws Exception {
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("LangChain4j 帮助 Java 应用调用大模型。"))
                .build());

        mvc.perform(post("/api/chat").contentType("application/json")
                        .content("{\"message\":\"什么是 LangChain4j？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("LangChain4j 帮助 Java 应用调用大模型。"));

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatModel).chat(captor.capture());
        assertThat(captor.getValue().messages()).hasSize(2);
        assertThat(captor.getValue().messages().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) captor.getValue().messages().get(0)).text()).contains("教学助手");
        assertThat(((UserMessage) captor.getValue().messages().get(1)).singleText())
                .isEqualTo("什么是 LangChain4j？");
    }

    @Test
    void shouldRejectBlankMessageBeforeCallingModel() throws Exception {
        mvc.perform(post("/api/chat").contentType("application/json")
                        .content("{\"message\":\"  \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(chatModel);
    }

    @Test
    void shouldRejectOversizedMessage() throws Exception {
        mvc.perform(post("/api/chat").contentType("application/json")
                        .content("{\"message\":\"" + "a".repeat(4001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(chatModel);
    }
}
