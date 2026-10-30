package com.example.ai.model;

import com.example.ai.stream.ChatStream;
import com.example.ai.repository.ChatHistoryRepository;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.exception.AsyncNotSupportedException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RecordedStreamingModelClient {
    private final ObjectProvider<StreamingChatModel> models;
    private final RecordedModelClient recorder;
    private final ChatHistoryRepository history;
    private final long timeoutMs;

    public RecordedStreamingModelClient(ObjectProvider<StreamingChatModel> models, RecordedModelClient recorder,
                                        ChatHistoryRepository history, @Value("${app.chat.stream-round-timeout-ms:90000}") long timeoutMs) {
        this.models = models; this.recorder = recorder; this.history = history; this.timeoutMs = timeoutMs;
    }

    public ChatResponse chat(ChatHistoryRepository.Run run, int round, ChatRequest request, ChatStream stream) {
        String id = recorder.start(run, round, request);
        BlockingQueue<ChatModelStreamingEvent> queue = new ArrayBlockingQueue<>(512);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        AtomicBoolean ended = new AtomicBoolean();
        AtomicBoolean receivedEvent = new AtomicBoolean();
        AtomicBoolean callbackStarted = new AtomicBoolean();
        StringBuilder partial = new StringBuilder();
        stream.onCancel(() -> { var sub = subscription.get(); if (sub != null) sub.cancel(); });
        try {
            stream.check();
            models.getObject().chat(request).subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription sub) {
                    subscription.set(sub); stream.onCancel(sub::cancel);
                    try { stream.check(); sub.request(Long.MAX_VALUE); }
                    catch (RuntimeException error) { sub.cancel(); failure.compareAndSet(null, error); }
                }
                public void onNext(ChatModelStreamingEvent event) {
                    receivedEvent.set(true);
                    // 模型线程仅写入有界队列，SSE 网络写入和数据库操作由执行线程处理。
                    if (event instanceof PartialResponse || event instanceof CompleteResponse) {
                        if (!queue.offer(event)) { failure.compareAndSet(null, new IllegalStateException("模型事件缓冲区已满")); subscription.get().cancel(); }
                    }
                }
                public void onError(Throwable error) {
                    // Spring RestClient 的部分版本只实现回调流接口。仅在不支持
                    // Publisher 且尚未产生事件时切换，不重试已发出的模型请求。
                    if (error instanceof AsyncNotSupportedException && !receivedEvent.get() && callbackStarted.compareAndSet(false, true)) {
                        callbackStream(models.getObject(), request, stream, queue, failure, subscription);
                    } else failure.compareAndSet(null, error);
                }
                public void onComplete() { ended.set(true); }
            });
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (true) {
                stream.checkAuthorization();
                Throwable error = failure.get();
                if (error != null) throw error instanceof RuntimeException runtime ? runtime : new IllegalStateException("模型流失败", error);
                if (System.nanoTime() > deadline) throw new ChatStream.Interrupted("等待模型流超时");
                ChatModelStreamingEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                if (event == null && ended.get()) throw new IllegalStateException("模型流缺少完整返回消息");
                if (event instanceof PartialResponse delta) {
                    partial.append(delta.text());
                    stream.emit("delta", Map.of("round", round, "text", delta.text()));
                } else if (event instanceof CompleteResponse complete) {
                    ChatResponse response = complete.chatResponse();
                    stream.check(); recorder.complete(id, response);
                    return response;
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            var interrupted = new ChatStream.Interrupted("执行线程被中断");
            recorder.fail(id, interrupted, history.json(Map.of("partialText", partial.toString(), "incomplete", true)));
            throw interrupted;
        } catch (RuntimeException error) {
            recorder.fail(id, error, history.json(Map.of("partialText", partial.toString(), "incomplete", true)));
            throw error;
        } finally {
            var sub = subscription.get(); if (sub != null) sub.cancel();
            stream.clearCancellation();
        }
    }

    private void callbackStream(StreamingChatModel model, ChatRequest request, ChatStream stream,
                                BlockingQueue<ChatModelStreamingEvent> queue, AtomicReference<Throwable> failure,
                                AtomicReference<Flow.Subscription> subscription) {
        try {
            stream.check();
            model.chat(request, new StreamingChatResponseHandler() {
                private void add(ChatModelStreamingEvent event) {
                    if (!queue.offer(event)) {
                        failure.compareAndSet(null, new IllegalStateException("模型事件缓冲区已满"));
                        var sub = subscription.get(); if (sub != null) sub.cancel();
                    }
                }
                public void onPartialResponse(String text) { add(new PartialResponse(text)); }
                public void onPartialResponse(PartialResponse response, PartialResponseContext context) {
                    var handle = context.streamingHandle();
                    subscription.set(new Flow.Subscription() {
                        public void request(long n) { }
                        public void cancel() { handle.cancel(); }
                    });
                    stream.onCancel(handle::cancel);
                    if (!handle.isCancelled()) add(response);
                }
                public void onCompleteResponse(ChatResponse response) { add(new CompleteResponse(response)); }
                public void onError(Throwable error) { failure.compareAndSet(null, error); }
            });
        } catch (RuntimeException error) { failure.compareAndSet(null, error); }
    }
}
