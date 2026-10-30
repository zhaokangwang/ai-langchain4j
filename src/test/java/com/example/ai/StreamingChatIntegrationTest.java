package com.example.ai;

import org.springframework.context.annotation.Import;

import com.example.ai.model.RecordedStreamingModelClient;
import com.example.ai.stream.ChatStream;
import com.example.ai.model.RecordedModelClient;
import com.example.ai.repository.ChatHistoryRepository;
import com.example.ai.service.CustomerChatService;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "langchain4j.open-ai.chat-model.api-key=test-key-not-used")
@AutoConfigureMockMvc
@Import(TestAuthenticatedRequests.class)
@WithBusinessIdentity
class StreamingChatIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired CustomerChatService service;
    @Autowired ChatHistoryRepository history;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecordedModelClient recorder;
    @Autowired ObjectProvider<StreamingChatModel> models;
    @MockitoBean StreamingChatModel streamingModel;
    @MockitoBean ChatModel blockingModel;

    private String id() { return UUID.randomUUID().toString(); }
    private ChatResponse answer(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).build(); }
    private Flow.Publisher<ChatModelStreamingEvent> publisher(ChatModelStreamingEvent... events) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            boolean delivered, cancelled;
            public void request(long n) {if(delivered)return;delivered=true;for(var event:events){if(cancelled)return;subscriber.onNext(event);}if(!cancelled)subscriber.onComplete();}
            public void cancel() {cancelled=true;}
        });
    }

    @Test
    void shouldStreamRealDeltasAndToolEventsThenPersistBeforeDoneAndReuseAcrossInterfaces() throws Exception {
        String request=id();
        var tool=ToolExecutionRequest.builder().id("load-call").name("loadSkill").arguments("{\"skillName\":\"order-query\"}").build();
        when(streamingModel.chat(any(ChatRequest.class))).thenReturn(
                publisher(new CompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(tool)).build())),
                publisher(new PartialResponse("你好"),new PartialResponse("，请提供订单号。"),new CompleteResponse(answer("你好，请提供订单号。"))));
        var result=mvc.perform(post("/api/chat/stream").contentType("application/json").accept("text/event-stream")
                        .content("{\"requestId\":\""+request+"\",\"message\":\"查订单\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10_000);
        String body=mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream")).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:accepted","event:delta","event:tool_start","event:tool_complete","event:done","你好");
        assertThat(body.indexOf("event:tool_start")).isLessThan(body.indexOf("event:tool_complete"));
        assertThat(body.indexOf("event:delta")).isLessThan(body.indexOf("event:done"));
        assertThat(history.find(request).orElseThrow().status()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM model_calls WHERE request_id=? AND status='COMPLETED'",Integer.class,request)).isEqualTo(2);
        verifyNoInteractions(blockingModel);
        var cached=service.chat(null,"查订单",request);
        assertThat(cached.answer()).isEqualTo("你好，请提供订单号。");
        verifyNoInteractions(blockingModel);
        verify(streamingModel,times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void shouldEmitDeltaBeforeModelCompletesAndCancelSubscriptionKeepingIncompleteAudit() throws Exception {
        String request=id();
        CountDownLatch deltaReceived=new CountDownLatch(1);
        AtomicBoolean upstreamCancelled=new AtomicBoolean();
        when(streamingModel.chat(any(ChatRequest.class))).thenReturn(subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            public void request(long n) {subscriber.onNext(new PartialResponse("未完成片段"));}
            public void cancel() {upstreamCancelled.set(true);}
        }));
        ChatStream stream=new ChatStream((event,data)->{if(event.equals("delta"))deltaReceived.countDown();});
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            var captured=service.prepareChat(null,"长回答",request,stream);
            var work=executor.submit(captured::get);
            assertThat(deltaReceived.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(work.isDone()).isFalse();
            stream.cancel("用户取消");
            assertThatThrownBy(()->work.get(10,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ChatStream.Interrupted.class);
            assertThat(upstreamCancelled).isTrue();
            assertThat(history.find(request).orElseThrow().status()).isEqualTo("INTERRUPTED");
            assertThat(jdbc.queryForObject("SELECT response_json FROM model_calls WHERE request_id=?",String.class,request)).contains("未完成片段","incomplete");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chat_messages WHERE request_id=?",Integer.class,request)).isEqualTo(1);
            assertThat(history.recentContext(history.find(request).orElseThrow().conversationId())).isEmpty();
        } finally {stream.cancel("测试结束");executor.shutdownNow();}
    }

    @Test
    void shouldExposeCancelEndpointAndWriteErrorEventInsteadOfFalseSuccess() throws Exception {
        String request=id();CountDownLatch subscribed=new CountDownLatch(1);
        when(streamingModel.chat(any(ChatRequest.class))).thenReturn(subscriber->subscriber.onSubscribe(new Flow.Subscription(){
            public void request(long n){subscribed.countDown();}
            public void cancel(){}
        }));
        var result=mvc.perform(post("/api/chat/stream").contentType("application/json")
                .content("{\"requestId\":\""+request+"\",\"message\":\"停止测试\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(subscribed.await(10,TimeUnit.SECONDS)).isTrue();
        mvc.perform(post("/api/chat/stream/{id}/cancel",request)).andExpect(status().isOk()).andExpect(jsonPath("$.cancelRequested").value(true));
        result.getAsyncResult(10_000);
        String body=mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:error","499").doesNotContain("event:done");
        assertThat(history.find(request).orElseThrow().status()).isEqualTo("INTERRUPTED");
    }

    @Test
    void shouldRecordProviderFailureAndLeaveOriginalJsonEndpointOperational() throws Exception {
        String request=id();
        when(streamingModel.chat(any(ChatRequest.class))).thenReturn(subscriber->subscriber.onSubscribe(new Flow.Subscription(){
            public void request(long n){subscriber.onError(new IllegalStateException("internal credentials must not leak"));}
            public void cancel(){}
        }));
        var result=mvc.perform(post("/api/chat/stream").contentType("application/json")
                .content("{\"requestId\":\""+request+"\",\"message\":\"错误测试\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10_000);
        String body=mvc.perform(asyncDispatch(result)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("event:error","502").doesNotContain("internal credentials","event:done");
        assertThat(history.find(request).orElseThrow().status()).isEqualTo("FAILED");
        when(blockingModel.chat(any(ChatRequest.class))).thenReturn(answer("JSON 正常"));
        mvc.perform(post("/api/chat").contentType("application/json").content("{\"message\":\"普通接口\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("JSON 正常"));
    }

    @Test
    void shouldTimeoutSilentModelAndRejectEmptyCompletedStream() {
        var client=new RecordedStreamingModelClient(models,recorder,history,50);
        when(streamingModel.chat(any(ChatRequest.class))).thenReturn(subscriber->subscriber.onSubscribe(new Flow.Subscription(){public void request(long n){}public void cancel(){}}));
        var run=history.begin(id(),null,"超时测试","CHAT");
        assertThatThrownBy(()->client.chat(run,1,ChatRequest.builder().messages(dev.langchain4j.data.message.UserMessage.from("测试")).build(),new ChatStream((name,data)->{})))
                .isInstanceOf(ChatStream.Interrupted.class);
        history.fail(run,new ChatStream.Interrupted("超时"));
        when(streamingModel.chat(any(ChatRequest.class))).thenReturn(publisher());
        assertThatThrownBy(()->service.chat(null,"空流",id(),new ChatStream((name,data)->{})))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("缺少完整");
    }
}
