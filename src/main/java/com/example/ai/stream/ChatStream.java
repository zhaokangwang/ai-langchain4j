package com.example.ai.stream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** 单次 SSE 连接的执行控制，不存用户身份或历史。 */
public class ChatStream {
    public static class Interrupted extends RuntimeException {
        public Interrupted(String reason) { super(reason); }
    }
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final BiConsumer<String, Object> sink;
    private volatile Runnable cancellation;
    private volatile String reason = "已停止生成";

    public ChatStream(BiConsumer<String, Object> sink) { this.sink = sink; }
    public void emit(String event, Object data) { check(); sink.accept(event, data); }
    public void onCancel(Runnable action) { cancellation = action; if (cancelled.get()) action.run(); }
    public void clearCancellation() { cancellation = null; }
    public void cancel(String reason) {
        this.reason = reason;
        cancelled.set(true);
        Runnable action = cancellation;
        if (action != null) action.run();
    }
    public void check() { if (cancelled.get()) throw new Interrupted(reason); }
}
