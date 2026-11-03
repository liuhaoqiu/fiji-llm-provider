/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.*;

/** JDK request timeouts do not bound a blocking SSE body read after headers arrive. */
final class IdleSseGuard implements ServerSentEventParser {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Fiji-LLM-stream-timeout"); t.setDaemon(true); return t;
    });
    private final ServerSentEventParser parser;
    private final ServerSentEventListener target;
    private final long timeoutNanos;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final AtomicInteger delivering = new AtomicInteger();
    private volatile long lastEvent = System.nanoTime();
    private volatile InputStream stream;
    private ScheduledFuture<?> timer;

    IdleSseGuard(ServerSentEventParser parser, ServerSentEventListener target, Duration timeout) {
        this.parser = parser; this.target = target;
        this.timeoutNanos = timeout == null || timeout.isZero() || timeout.isNegative() ? 0 : timeout.toNanos();
    }
    final ServerSentEventListener listener = new ServerSentEventListener() {
        @Override public void onOpen(SuccessfulHttpResponse response) { if (!ended.get()) target.onOpen(response); }
        @Override public void onEvent(ServerSentEvent event) { deliver(() -> target.onEvent(event)); }
        @Override public void onEvent(ServerSentEvent event, ServerSentEventContext context) {
            deliver(() -> target.onEvent(event, context));
        }
        @Override public void onError(Throwable error) {
            if (ended.compareAndSet(false, true)) target.onError(error);
        }
        @Override public void onClose() {
            if (ended.compareAndSet(false, true)) target.onClose();
        }
    };
    private void deliver(Runnable callback) {
        delivering.incrementAndGet();
        try {
            if (!ended.get()) { lastEvent = System.nanoTime(); callback.run(); }
        } finally { lastEvent = System.nanoTime(); delivering.decrementAndGet(); }
    }
    @Override public void parse(InputStream input, ServerSentEventListener ignored) {
        stream = input; lastEvent = System.nanoTime();
        if (timeoutNanos > 0) {
            long period = Math.max(10, Math.min(1000, TimeUnit.NANOSECONDS.toMillis(timeoutNanos) / 4));
            timer = TIMER.scheduleAtFixedRate(this::check, period, period, TimeUnit.MILLISECONDS);
        }
        try { parser.parse(input, listener); }
        finally {
            if (timer != null) timer.cancel(false);
        }
    }
    private void check() {
        if (ended.get() || delivering.get() != 0 || System.nanoTime() - lastEvent < timeoutNanos) return;
        if (!ended.compareAndSet(false, true)) return;
        try {
            target.onError(new HttpTimeoutException("SSE stream idle timeout: no data events for " +
                TimeUnit.NANOSECONDS.toSeconds(timeoutNanos) + " seconds; keep-alive comments are not model output"));
        } finally {
            try { if (stream != null) stream.close(); } catch (java.io.IOException ignored) {}
            if (timer != null) timer.cancel(false);
        }
    }
}
