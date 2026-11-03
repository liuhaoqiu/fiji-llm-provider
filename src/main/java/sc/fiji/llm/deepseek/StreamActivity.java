/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Reports observed activity, never claims that an idle connection is making progress. */
final class StreamActivity implements AutoCloseable {
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Fiji-LLM-activity-monitor"); t.setDaemon(true); return t;
    });
    private final LongSupplier clock;
    private final Consumer<String> notice;
    private final long interval;
    private final Map<String,String> tools = new LinkedHashMap<>();
    private long lastEvent, lastNotice;
    private String phase = "Waiting for API output", lastTool = "none";
    private boolean active;
    private ScheduledFuture<?> timer;

    StreamActivity(Consumer<String> notice) {
        this(notice, System::nanoTime, Duration.ofSeconds(60));
    }
    StreamActivity(Consumer<String> notice, LongSupplier clock, Duration interval) {
        this.notice = notice; this.clock = clock; this.interval = interval.toNanos();
    }
    synchronized void start() {
        active = true; lastEvent = lastNotice = clock.getAsLong();
        timer = TIMER.scheduleAtFixedRate(this::tick, 10, 10, TimeUnit.SECONDS);
    }
    synchronized void request() { event("Waiting for API output"); }
    synchronized void output() { event("Receiving model output"); }
    synchronized void toolStarted(String id, String name) {
        tools.put(id, name); event("Tool running: " + name);
    }
    synchronized void toolFinished(String id, String name) {
        tools.remove(id); lastTool = name;
        event(tools.isEmpty() ? "Waiting for API output" : "Tool running: " + String.join(", ", tools.values()));
    }
    private void event(String next) { phase = next; lastEvent = clock.getAsLong(); }
    synchronized String status() {
        long seconds = Math.max(0, (clock.getAsLong() - lastEvent) / 1_000_000_000L);
        return (active ? phase : "Stopped / completed") + "\nSeconds since last model output or tool event: " +
            seconds + "\nLast finished tool: " + lastTool;
    }
    synchronized void tick() {
        if (!active) return;
        long now = clock.getAsLong();
        if (now - lastEvent < interval || now - lastNotice < interval) return;
        lastNotice = now;
        long seconds = Math.max(0, (now - lastEvent) / 1_000_000_000L);
        String detail = tools.isEmpty()
            ? "等待 API 新输出 / Waiting for new API output: " + seconds + " s without new model output or tool events. " +
                "The client cannot tell whether the provider is queuing, generating, or the connection has stalled. " +
                "Last finished tool: " + lastTool + ". No new task progress is confirmed."
            : "等待工具返回 / Waiting for tool result: " + String.join(", ", tools.values()) + "; " + seconds +
                " s without a new tool event. The tool has not reported completion.";
        notice.accept("\n\n[Plugin status, not model reasoning] " + detail + "\n\n");
    }
    @Override public synchronized void close() {
        active = false;
        if (timer != null) timer.cancel(false);
    }
}
