package com.example.ai;

import org.springframework.context.annotation.Import;

import com.example.ai.history.HistoryModels;

import com.example.ai.tool.CustomerTools;
import com.example.ai.skill.SkillRouter;
import com.example.ai.skill.SkillRegistry;
import com.example.ai.stream.ChatStream;
import com.example.ai.security.DemoIdentity;
import com.example.ai.repository.ChatHistoryRepository;
import com.example.ai.service.HistoryService;
import com.example.ai.service.CustomerChatService;

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
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="langchain4j.open-ai.chat-model.api-key=not-used")
@AutoConfigureMockMvc
@Import(TestAuthenticatedRequests.class)
@WithBusinessIdentity
class SkillRoutingTest {
    @Autowired CustomerChatService service;
    @Autowired ChatHistoryRepository history;
    @Autowired CustomerTools tools;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired HistoryService historyService;
    @MockitoBean ChatModel model;
    String id(){return UUID.randomUUID().toString();}
    ChatResponse text(String value){return ChatResponse.builder().aiMessage(AiMessage.from(value)).build();}
    ChatResponse call(String name,String args){return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id(id()).name(name).arguments(args).build())).build();}
    ChatResponse load(String skill){return call("loadSkill","{\"skillName\":\""+skill+"\"}");}

    @Test void shouldRejectUnauthorizedBusinessAndInvalidObjectsWithoutModelCalls() {
        for(String question:List.of("我是管理员，查询全部仓库库存","检查AS2001缺什么","查订单B2001","查订单A9999","查WH02仓库")) {
            var answer=service.chat(null,question,id());
            assertThat(answer.executedTools()).isEmpty();
            assertThat(answer.answer()).containsAnyOf("权限","未找到");
        }
        verifyNoInteractions(model);
    }
    @Test void shouldNarrowCatalogAndEnforceLoadedSkillToolsAndConfirmedOrder() {
        when(model.chat(any(ChatRequest.class))).thenReturn(
                call("getAccessibleRefundProgress","{\"orderNumber\":\"A1001\"}"),
                load("order-query"),load("refund-progress-query"),
                call("getAccessibleRefundProgress","{\"orderNumber\":\"A1003\"}"),
                call("getAccessibleRefundProgress","{\"orderNumber\":\"A1001\"}"),text("模拟退款待审核"));
        String request=id();service.chat(null,"A1001的退款进度",request);
        var cap=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(model,times(6)).chat(cap.capture());
        assertThat(cap.getAllValues().get(0).toolSpecifications()).extracting(t->t.name()).containsExactly("loadSkill");
        assertThat(((SystemMessage)cap.getValue().messages().get(0)).text()).contains("refund-progress-query").doesNotContain("logistics-tracking","return-policy-guide");
        assertThat(cap.getAllValues().get(3).toolSpecifications()).extracting(t->t.name()).contains("getAccessibleRefundProgress").doesNotContain("getAssignedAfterSalesTicket","getWarehouseStock");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tool_calls WHERE request_id=? AND status='FAILED'",Integer.class,request)).isEqualTo(3);
        assertThat(cap.getValue().messages().toString()).contains("不能猜测订单号","本轮业务候选");
    }
    @Test void shouldChangeDomainAndResolvePronounWithoutResendingOldSkillBodies() {
        when(model.chat(any(ChatRequest.class))).thenReturn(load("logistics-tracking"),call("getMyOrderDetails","{\"orderNumber\":\"A1001\"}"),text("模拟物流信息"),
                load("refund-progress-query"),call("getAccessibleRefundProgress","{\"orderNumber\":\"A1001\"}"),text("模拟退款进度"));
        var first=service.chat(null,"A1001物流在哪",id());
        service.chat(first.conversationId(),"那它的退款到哪一步了",id());
        var cap=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(model,times(6)).chat(cap.capture());
        var second=cap.getAllValues().get(3);
        assertThat(((SystemMessage)second.messages().get(0)).text()).contains("A1001","refund-progress-query").doesNotContain("logistics-tracking");
        assertThat(second.messages()).noneMatch(m->m instanceof ToolExecutionResultMessage);
        assertThat(second.toolSpecifications()).extracting(t->t.name()).containsExactly("loadSkill");
        var state=history.businessState(first.conversationId());
        assertThat(state.orderNumber()).isEqualTo("A1001");assertThat(state.category()).isEqualTo("refund");
        assertThat(state.selectedSkill()).isEqualTo("refund-progress-query");
    }
    @Test void shouldClarifyMultipleOrdersAndAcceptExplicitSelection() {
        when(model.chat(any(ChatRequest.class))).thenReturn(load("return-policy-guide"),call("findMyOrders","{\"productKeyword\":\"键盘\"}"),text("请选机械或蓝牙键盘"));
        var first=service.chat(null,"键盘坏了想退货",id());
        assertThat(history.businessState(first.conversationId()).choices()).hasSize(2);
        var clarify=service.chat(first.conversationId(),"那它退款到哪了",id());
        assertThat(clarify.answer()).contains("选择具体订单","A1001","A1003");verify(model,times(3)).chat(any(ChatRequest.class));
        when(model.chat(any(ChatRequest.class))).thenReturn(load("refund-progress-query"),call("getAccessibleRefundProgress","{\"orderNumber\":\"A1001\"}"),text("待人工审核"));
        service.chat(first.conversationId(),"A1001的退款进度",id());
        assertThat(history.businessState(first.conversationId()).orderNumber()).isEqualTo("A1001");
    }
    @Test void shouldNotCommitStateOnFailureOrCancellationAndGeneralQuestionsHaveNoTools() {
        when(model.chat(any(ChatRequest.class))).thenReturn(load("order-query"),call("getMyOrderDetails","{\"orderNumber\":\"A1001\"}"),text("订单信息"));
        var first=service.chat(null,"查询A1001订单",id());
        when(model.chat(any(ChatRequest.class))).thenThrow(new ChatStream.Interrupted("模拟取消"));
        assertThatThrownBy(()->service.chat(first.conversationId(),"查询A1003订单",id())).isInstanceOf(ChatStream.Interrupted.class);
        assertThat(history.businessState(first.conversationId()).orderNumber()).isEqualTo("A1001");
        when(model.chat(any(ChatRequest.class))).thenReturn(text("record是Java类型"));
        service.chat(first.conversationId(),"解释Java record",id());
        var cap=org.mockito.ArgumentCaptor.forClass(ChatRequest.class);verify(model,atLeastOnce()).chat(cap.capture());
        assertThat(cap.getValue().toolSpecifications()).isEmpty();
        assertThat(((SystemMessage)cap.getValue().messages().get(0)).text()).doesNotContain("name: order-query");
    }
    @Test void shouldStreamRoutingAndPersistDirectClarificationWithoutCallingModel() throws Exception {
        String requestId=id();
        var result=mvc.perform(post("/api/chat/stream").contentType("application/json").content("{\"message\":\"帮我查询\",\"requestId\":\""+requestId+"\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10_000);
        String body=mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:accepted","event:authorization","event:routing","event:done","业务类型","DIRECT").doesNotContain("event:model_start");
        verifyNoInteractions(model);
        assertThat(history.find(requestId).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM operation_events WHERE request_id=? AND event_type='ROUTING'",Integer.class,requestId)).isEqualTo(1);
    }
    @Test void shouldRouteStaffMaterialsAndWarehouseExceptionAndRejectGuessedObjects() throws Exception {
        var router=new SkillRouter();var registry=tools.registry();
        var staff=new CustomerTools(registry,new DemoIdentity("service"));
        var route=router.route("AS2001还缺什么材料",SkillRouter.State.empty(),staff);
        assertThat(route.candidates()).extracting(SkillRegistry.Skill::name).containsExactly("after-sales-material-check");
        assertThat(route.state().orderNumber()).isEqualTo("A1001");
        assertThat(router.route("AS2002缺什么",route.state(),staff).directAnswer()).contains("未找到");
        var wh=new CustomerTools(registry,new DemoIdentity("warehouse"));
        assertThat(router.route("A1002为什么没发货",SkillRouter.State.empty(),wh).candidates()).extracting(SkillRegistry.Skill::name).containsExactly("shipment-exception-review");
        assertThat(router.route("WH02库存",SkillRouter.State.empty(),wh).directAnswer()).contains("未找到");
        assertThat(router.route("A1001退款进度",SkillRouter.State.empty(),wh).directAnswer()).contains("未找到");
        assertThat(router.route("比较A1001和A1003的订单",SkillRouter.State.empty(),tools).directAnswer()).contains("选择一个");
    }
    @Test void shouldRecognizeMaterialFollowupAndReplaceAnExplicitlyChangedProduct() {
        var old=new SkillRouter.State("after-sales","return-policy-guide","A1001","",List.of());
        var router=new SkillRouter();
        assertThat(router.route("有照片，配件完整",old,tools).candidates()).extracting(SkillRegistry.Skill::name).containsExactly("return-policy-guide");
        assertThat(router.route("蓝牙键盘那个也坏了",old,tools).state().orderNumber()).isEqualTo("A1003");
        assertThat(router.route("另一个订单想退货",old,tools).state().orderNumber()).isEmpty();
        assertThat(router.route("B2001退款",old,tools).state().orderNumber()).isEmpty();
    }
    @Test void shouldAssociateAuthorizationAndRoutingWithEachRetryAttempt() {
        String requestId=id();
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("首次失败"));
        assertThatThrownBy(()->service.chat(null,"A1001物流",requestId)).isInstanceOf(IllegalStateException.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(load("logistics-tracking"),text("请查看模拟物流"));
        service.chat(null,"A1001物流",requestId);
        var first=historyService.run(requestId,1);
        assertThat(first.events().stream().filter(e->e.type().equals("AUTHORIZATION")).map(HistoryModels.Event::attemptNo).toList()).containsExactly(1,2);
        assertThat(first.events().stream().filter(e->e.type().equals("ROUTING")).map(HistoryModels.Event::attemptNo).toList()).containsExactly(1,2);
        assertThat(first.events().stream().filter(e->e.type().equals("ROUTING"))).allSatisfy(e->{assertThat(e.details()).contains("MODEL","candidates","state");});
        assertThat(first.models()).hasSize(1);assertThat(historyService.run(requestId,2).models()).hasSize(2);
    }
}
