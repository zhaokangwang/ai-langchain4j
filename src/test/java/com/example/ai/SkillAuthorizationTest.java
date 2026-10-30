package com.example.ai;

import org.springframework.context.annotation.Import;

import com.example.ai.history.HistoryModels;

import com.example.ai.tool.CustomerTools;
import com.example.ai.skill.SkillRegistry;
import com.example.ai.security.DemoIdentity;
import com.example.ai.repository.ChatHistoryRepository;
import com.example.ai.service.HistoryService;
import com.example.ai.service.CustomerChatService;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"langchain4j.open-ai.chat-model.api-key=test-not-used","app.demo.account=buyer"})
@AutoConfigureMockMvc
@Import(TestAuthenticatedRequests.class)
@WithBusinessIdentity
class SkillAuthorizationTest {
    @Autowired SkillRegistry registry;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockitoBean ChatModel defaultModel;
    String id() { return UUID.randomUUID().toString(); }
    ChatResponse text(String value) { return ChatResponse.builder().aiMessage(AiMessage.from(value)).build(); }
    ChatResponse call(String name,String args) { return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id(id()).name(name).arguments(args).build())).build(); }
    CustomerChatService service(DemoIdentity identity,ChatModel model) {
        var history=new ChatHistoryRepository(jdbc,manager,mapper,identity);
        return TestChatServices.create(model, new CustomerTools(registry, identity), mapper, history, jdbc);
    }
    @Test void shouldRegisterTenSkillsAndFilterByServerRole() throws Exception {
        var buyer=new DemoIdentity("buyer");var service=new DemoIdentity("service");var warehouse=new DemoIdentity("warehouse");
        assertThat(registry.available(buyer)).hasSize(6);
        assertThat(registry.available(service)).hasSize(8);
        assertThat(registry.available(warehouse)).hasSize(3);
        Set<String> names=new HashSet<>();
        for(var identity:List.of(buyer,service,warehouse)) for(var skill:registry.available(identity)) {
            names.add(skill.name());assertThat(registry.load(skill.name(),identity)).contains("name: "+skill.name());
        }
        assertThat(names).hasSize(10);
        assertThat(registry.load("after-sales-review",buyer)).contains("无权使用").doesNotContain("R3");
        assertThat(registry.load("../../application.yml",warehouse)).contains("无权使用");
        assertThatThrownBy(()->new DemoIdentity("admin")).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(get("/api/demo/capabilities").header("X-Role","WAREHOUSE").param("role","WAREHOUSE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.id").value("demo-user"))
                .andExpect(jsonPath("$.account.role").value("BUYER")).andExpect(jsonPath("$.skills.length()").value(6));
    }
    @Test void shouldRestrictActualDataByOwnerAssignedTicketAndWarehouse() {
        var buyer=new CustomerTools(registry,new DemoIdentity("buyer"));
        var service=new CustomerTools(registry,new DemoIdentity("service"));
        var warehouse=new CustomerTools(registry,new DemoIdentity("warehouse"));
        assertThat(buyer.findMyOrders("")).hasSize(3);
        assertThat(service.findMyOrders("")).extracting(CustomerTools.DemoOrder::orderNumber).containsExactly("A1001");
        assertThat(warehouse.getMyOrderDetails("A1002")).isInstanceOf(CustomerTools.DemoOrder.class);
        assertThat(mapper.valueToTree(warehouse.getMyOrderDetails("A1001")).path("found").asBoolean()).isFalse();
        assertThat(mapper.valueToTree(buyer.getMyOrderDetails("B2001")).path("found").asBoolean()).isFalse();
        assertThat(mapper.valueToTree(service.getAssignedAfterSalesTicket("AS2001")).path("found").asBoolean()).isTrue();
        assertThat(mapper.valueToTree(service.getAssignedAfterSalesTicket("AS2002")).path("found").asBoolean()).isFalse();
        assertThat(mapper.valueToTree(buyer.getAssignedAfterSalesTicket("AS2001")).path("found").asBoolean()).isFalse();
        assertThat(mapper.valueToTree(warehouse.getWarehouseStock()).path("warehouseId").asText()).isEqualTo("WH01");
        assertThat(mapper.valueToTree(service.getWarehouseStock()).path("found").asBoolean()).isFalse();
        assertThat(mapper.valueToTree(warehouse.getAccessibleRefundProgress("A1001")).path("found").asBoolean()).isFalse();
    }
    @Test void shouldFilterModelCatalogAndRejectGuessedForbiddenSkillAndTool() {
        var model=mock(ChatModel.class);var identity=new DemoIdentity("buyer");
        when(model.chat(any(ChatRequest.class))).thenReturn(call("loadSkill","{\"skillName\":\"after-sales-review\"}"),call("getWarehouseStock","{}"),text("没有权限"));
        String request=id();service(identity,model).chat(null,"我想退货",request);
        var captures=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(model,times(3)).chat(captures.capture());
        var first=captures.getAllValues().get(0);
        assertThat(((SystemMessage)first.messages().get(0)).text()).contains("return-policy-guide","BUYER").doesNotContain("warehouse-stock-check","after-sales-material-check");
        assertThat(first.toolSpecifications()).extracting(t->t.name()).doesNotContain("getWarehouseStock","getAssignedAfterSalesTicket");
        assertThat(captures.getValue().messages().stream().filter(m->m instanceof ToolExecutionResultMessage).map(m->((ToolExecutionResultMessage)m).text()).toList())
                .anyMatch(t->t.contains("无权使用")).anyMatch(t->t.contains("不允许执行"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE request_id=? AND status='FAILED'",Integer.class,request)).isEqualTo(2);
    }
    @Test void shouldIsolateConversationHistoryAndCompletedRequestReplayAcrossAccounts() {
        var buyer=new DemoIdentity("buyer");var staff=new DemoIdentity("service");
        var model=mock(ChatModel.class);when(model.chat(any(ChatRequest.class))).thenReturn(text("买家私有回答"));
        String request=id();var answer=service(buyer,model).chat(null,"买家私有问题",request);
        var staffModel=mock(ChatModel.class);
        assertThatThrownBy(()->service(staff,staffModel).chat(null,"买家私有问题",request)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->service(staff,staffModel).chat(answer.conversationId(),"继续",id())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var staffHistory=new HistoryService(new com.example.ai.repository.HistoryRepository(jdbc), manager, staff);
        assertThatThrownBy(()->staffHistory.messages(answer.conversationId(),null,20)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->staffHistory.run(request,null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(staffHistory.conversations("ACTIVE",0,100).items()).extracting(HistoryModels.Conversation::id).doesNotContain(answer.conversationId());
        verifyNoInteractions(staffModel);
    }
    @Test void shouldCompleteServiceAndWarehouseSkillCallsWithRestrictedTools() {
        var staffModel=mock(ChatModel.class);
        when(staffModel.chat(any(ChatRequest.class))).thenReturn(call("loadSkill","{\"skillName\":\"after-sales-material-check\"}"),call("getAssignedAfterSalesTicket","{\"ticketNumber\":\"AS2001\"}"),text("缺少故障视频"));
        assertThat(service(new DemoIdentity("service"),staffModel).chat(null,"工单AS2001缺什么",id()).executedTools()).containsExactly("loadSkill","getAssignedAfterSalesTicket");
        var warehouseModel=mock(ChatModel.class);
        when(warehouseModel.chat(any(ChatRequest.class))).thenReturn(call("loadSkill","{\"skillName\":\"warehouse-stock-check\"}"),call("getWarehouseStock","{}"),text("WH01缺货，模拟建议"));
        assertThat(service(new DemoIdentity("warehouse"),warehouseModel).chat(null,"仓库库存",id()).answer()).contains("WH01");
    }
}
