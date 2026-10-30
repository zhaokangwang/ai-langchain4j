package com.example.ai;

import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@AutoConfigureMockMvc
@Import(TestAuthenticatedRequests.class)
@WithBusinessIdentity
class CustomerConversationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ChatModel model;

    private ChatResponse text(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
    }

    private ChatResponse call(String name, String args) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id(java.util.UUID.randomUUID().toString()).name(name).arguments(args).build())).build();
    }

    @Test
    void shouldLoadSkillFindCandidatesAndContinueSelectedOrderInSameConversation() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                call("loadSkill", "{\"skillName\":\"return-policy-guide\"}"),
                call("findMyOrders", "{\"productKeyword\":\"键盘\"}"),
                text("你指的是机械键盘还是蓝牙键盘？"),
                call("getMyOrderDetails", "{\"orderNumber\":\"A1001\"}"),
                text("机械键盘签收 1 天，请补充按键故障视频。仅为教学建议，尚未退款。"));
        String json = mvc.perform(post("/api/chat").contentType("application/json")
                        .content("{\"message\":\"键盘坏了想退货\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executedTools[0]").value("loadSkill"))
                .andExpect(jsonPath("$.executedTools[1]").value("findMyOrders"))
                .andReturn().getResponse().getContentAsString();
        String id = mapper.readTree(json).path("conversationId").asText();
        mvc.perform(post("/api/chat").contentType("application/json")
                        .content(mapper.writeValueAsString(java.util.Map.of("conversationId", id, "message", "机械键盘那个"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executedTools[0]").value("getMyOrderDetails"));
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(5)).chat(captor.capture());
        var messages = captor.getAllValues().get(4).messages();
        assertThat(((SystemMessage)messages.get(0)).text()).contains("R3", "退货条件");
        assertThat(messages).anySatisfy(m -> {
            assertThat(m).isInstanceOf(ToolExecutionResultMessage.class);
            assertThat(((ToolExecutionResultMessage)m).text()).contains("daysSinceDelivery", "A1001");
        });
        assertThat(messages).anySatisfy(m -> {
            assertThat(m).isInstanceOf(UserMessage.class);
            assertThat(((UserMessage)m).singleText()).isEqualTo("键盘坏了想退货");
        });
        assertThat(messages.toString()).doesNotContain("B2001");
    }

    @Test
    void shouldIsolateNewConversationsAndAnswerGeneralQuestionsWithoutTools() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(text("第一轮回答"), text("第二个会话回答"));
        mvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"第一段私人上下文\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"解释依赖注入\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.executedTools").isEmpty());
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(2)).chat(captor.capture());
        assertThat(captor.getAllValues().get(1).messages()).hasSize(2);
        assertThat(captor.getAllValues().get(1).messages().toString()).doesNotContain("第一段私人上下文");
    }

    @Test
    void shouldRejectUnregisteredSkillAndRecoverMalformedArguments() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                call("loadSkill", "{\"skillName\":\"../../application.yml\"}"),
                call("findMyOrders", "not-json"), text("请重新提供信息"));
        mvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"查询订单\"}"))
                .andExpect(status().isOk());
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model, times(3)).chat(captor.capture());
        assertThat(captor.getAllValues().get(2).messages().toString())
                .contains("技能不存在", "工具参数无效").doesNotContain("DEEPSEEK_API_KEY");
    }

    @Test
    void shouldBoundRepeatedModelToolCalls() throws Exception {
        when(model.chat(any(ChatRequest.class))).thenReturn(call("findMyOrders", "{\"productKeyword\":\"键盘\"}"));
        mvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"查询订单\"}"))
                .andExpect(status().isBadGateway());
        verify(model, times(8)).chat(any(ChatRequest.class));
    }
}
