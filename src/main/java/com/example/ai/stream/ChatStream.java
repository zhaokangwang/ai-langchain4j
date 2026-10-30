package com.example.ai.stream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** 单次 SSE 连接的取消与授权复核控制，不保存聊天历史。 */
public class ChatStream {
    public static class Interrupted extends RuntimeException {
        public Interrupted(String reason) { super(reason); }
    }
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final BiConsumer<String, Object> sink;
    private volatile Runnable cancellation;
    private volatile String reason = "已停止生成";
    private Runnable authorization = () -> { };
    private long nextAuthorizationCheck;

    public ChatStream(BiConsumer<String, Object> sink) { this.sink = sink; }
    public void emit(String event, Object data) { check(); sink.accept(event, data); }
    public void onCancel(Runnable action) { cancellation = action; if (cancelled.get()) action.run(); }
    public void clearCancellation() { cancellation = null; }
    public void authorizeWith(Runnable action) { authorization = action; nextAuthorizationCheck = 0; }
    /** 只在模型事件消费线程复核，每秒至多一次；回调线程继续仅写队列。 */
    public void checkAuthorization() {
        check();
        long now = System.nanoTime();
        if (nextAuthorizationCheck == 0 || now - nextAuthorizationCheck >= 0) {
            authorization.run();
            nextAuthorizationCheck = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        }
    }
    public void cancel(String reason) {
        this.reason = reason;
        cancelled.set(true);
        Runnable action = cancellation;
        if (action != null) action.run();
    }
    public void check() { if (cancelled.get()) throw new Interrupted(reason); }
}
