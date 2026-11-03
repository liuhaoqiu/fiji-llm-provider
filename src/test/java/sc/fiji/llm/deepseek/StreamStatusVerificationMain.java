/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.io.*;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;

/** Actual stalled SSE body after headers, truthful status, and preserved paused replies. */
public final class StreamStatusVerificationMain {
    public interface Assistant { TokenStream stream(String prompt); }
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("empty-only")) {
            verifyEmptyApiReply();
            System.out.println("PASS: real empty/incomplete Responses reply becomes a visible paused checkpoint");
            return;
        }
        verifyStatus();
        verifyNetwork();
        verifyPausedReply();
        verifyEmptyApiReply();
        check(!BoundedContext.isTransportFailure(new IllegalStateException("archive write", new IOException("disk failure"))),
            "archive failure must not be mislabeled as a network interruption");
        System.out.println("PASS: idle API/tool status, SSE body silence and keepalive timeout, active streams/tools, preserved partial progress and one terminal callback");
    }
    private static void verifyStatus() {
        var now = new AtomicLong(); var notices = new ArrayList<String>();
        var activity = new StreamActivity(notices::add, now::get, Duration.ofSeconds(60));
        try {
            activity.start(); activity.request();
            now.set(Duration.ofSeconds(59).toNanos()); activity.tick();
            check(notices.isEmpty(), "no idle notice during normal short waits");
            now.set(Duration.ofSeconds(61).toNanos()); activity.tick();
            check(notices.size()==1 && notices.get(0).contains("cannot tell") && notices.get(0).contains("not model reasoning"),
                "wait status must be factual and separated from model reasoning");
            activity.output(); now.addAndGet(Duration.ofSeconds(59).toNanos()); activity.tick();
            check(notices.size()==1, "model output resets inactivity");
            activity.toolStarted("a", "Cellpose"); now.addAndGet(Duration.ofSeconds(61).toNanos()); activity.tick();
            check(notices.get(notices.size()-1).contains("Waiting for tool result: Cellpose"), "tool wait is distinct from API wait");
            activity.toolFinished("a", "Cellpose"); now.addAndGet(Duration.ofSeconds(61).toNanos()); activity.tick();
            check(notices.get(notices.size()-1).contains("Last finished tool: Cellpose"), "last finished tool is visible");
            int count = notices.size(); activity.close(); now.addAndGet(Duration.ofSeconds(120).toNanos()); activity.tick();
            check(notices.size()==count, "no heartbeat after completion");
        } finally { activity.close(); }
    }
    private static void verifyNetwork() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                if (exchange.getRequestURI().getPath().contains("active")) {
                    for (int i=0;i<8;i++) {
                        out.write(("data: tick-"+i+"\n\n").getBytes(StandardCharsets.UTF_8)); out.flush(); Thread.sleep(100);
                    }
                } else {
                    out.write(": headers received\n\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                    if (exchange.getRequestURI().getPath().contains("silent")) Thread.sleep(1500);
                    else for (int i=0;i<30;i++) {
                        out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); Thread.sleep(50);
                    }
                }
            } catch (IOException ignored) { /* client timeout closes the response body */ }
              catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String root = "http://127.0.0.1:"+server.getAddress().getPort();
            for (String route : List.of("silent", "keepalive", "active", "active-tool")) {
                var done = new CountDownLatch(1); var errors = new AtomicInteger(); var events = new AtomicInteger();
                var failure = new AtomicReference<Throwable>();
                var client = new BudgetHttpClientBuilder(new JdkHttpClientBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofMillis(350)), 10000).build();
                var listener = new ServerSentEventListener() {
                    @Override public void onEvent(ServerSentEvent event) {
                        events.incrementAndGet();
                        if (route.equals("active-tool")) try { Thread.sleep(500); }
                            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    }
                    @Override public void onError(Throwable error) { failure.set(error); errors.incrementAndGet(); done.countDown(); }
                    @Override public void onClose() { done.countDown(); }
                };
                client.execute(HttpRequest.builder().method(HttpMethod.POST).url(root+"/"+route).body("{}").build(),
                    new DefaultServerSentEventParser(), listener);
                check(done.await(10, TimeUnit.SECONDS), "stream must terminate: "+route);
                if (route.startsWith("active")) {
                    check(failure.get()==null && events.get()==8, "ongoing events/long synchronous tool callback survive: "+route);
                } else {
                    check(failure.get() instanceof HttpTimeoutException && errors.get()==1, "idle body must fail once: "+route+": "+failure.get());
                }
            }
        } finally { server.stop(0); pool.shutdownNow(); }
    }
    @SuppressWarnings("unchecked")
    private static void verifyPausedReply() throws Exception {
        var context = new BoundedContext(24000, 1_048_576);
        var memory = MessageWindowChatMemory.withMaxMessages(20);
        var request = ToolExecutionRequest.builder().id("edit-1").name("fiji_script_replace_content").arguments("{}").build();
        var messages = List.<ChatMessage>of(UserMessage.from("Count the original nuclei; compare with Cellpose"),
            AiMessage.from(request), ToolExecutionResultMessage.from(request, "macro edited; not rerun yet"));
        messages.forEach(memory::add); context.prepare(ChatRequest.builder().messages(messages).build());
        Map<String,Object> callbacks = new HashMap<>(); var starts = new AtomicInteger();
        TokenStream source = (TokenStream)Proxy.newProxyInstance(TokenStream.class.getClassLoader(), new Class<?>[]{TokenStream.class},
            (p,m,a) -> {
                if (!m.getName().equals("start")) { if (a!=null && a.length>0) callbacks.put(m.getName(), a[0]); return p; }
                starts.incrementAndGet();
                ((Consumer<PartialThinking>)callbacks.get("onPartialThinking")).accept(new PartialThinking("PARTIAL_LOG_RETAINED"));
                ((Consumer<Throwable>)callbacks.get("onError")).accept(new HttpTimeoutException("SSE stream idle timeout"));
                ((Consumer<Throwable>)callbacks.get("onError")).accept(new IOException("late duplicate failure"));
                ((Consumer<ChatResponse>)callbacks.get("onCompleteResponse")).accept(ChatResponse.builder().aiMessage(AiMessage.from("LATE_COMPLETION" )).build());
                return null;
            });
        var wrapped = ProgressAssistant.wrap(Assistant.class, prompt -> source, context, memory);
        var thinking = new StringBuilder(); var text = new StringBuilder(); var completed = new AtomicInteger(); var errors = new AtomicInteger();
        wrapped.stream("start").onPartialThinking(p -> thinking.append(p.text())).onPartialResponse(text::append)
            .onError(e -> errors.incrementAndGet()).onCompleteResponse(r -> completed.incrementAndGet()).start();
        check(thinking.toString().contains("PARTIAL_LOG_RETAINED"), "partial activity is retained");
        check(text.toString().contains("Task paused") && text.toString().contains("not completed") &&
            text.toString().contains("macro edited; not rerun yet") && text.toString().contains("No tools were automatically replayed"),
            "factual paused output retains the task and last result");
        check(completed.get()==1 && errors.get()==0 && starts.get()==1, "one saved paused reply; no automatic request/tool replay");
        check(((AiMessage)memory.messages().get(memory.messages().size()-1)).text().contains("Task paused"), "checkpoint in same-chat memory");
        check(java.nio.file.Files.readString(context.archive.directory.resolve("checkpoint.md")).contains("Cellpose"), "full local checkpoint retained");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void verifyEmptyApiReply() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] payload = ("event: response.incomplete\ndata: {\"type\":\"response.incomplete\",\"response\":" +
                "{\"id\":\"fixture-empty\",\"model\":\"fixture\",\"status\":\"incomplete\",\"output\":[]," +
                "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, payload.length);
            try (var out=exchange.getResponseBody()) { out.write(payload); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var context = new BoundedContext(24000, 1_048_576);
            var memory = MessageWindowChatMemory.withMaxMessages(20);
            var model = dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel.builder()
                .baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).apiKey("fixture-only")
                .httpClientBuilder(new BudgetHttpClientBuilder(new JdkHttpClientBuilder()
                    .readTimeout(Duration.ofSeconds(2)), 10000)).modelName("fixture").build();
            Assistant delegate = dev.langchain4j.service.AiServices.builder(Assistant.class)
                .streamingChatModel(model).chatMemory(memory)
                .chatRequestTransformer((request,id) -> context.prepare(request)).build();
            var wrapped = ProgressAssistant.wrap(Assistant.class, delegate, context, memory);
            var done = new CountDownLatch(1); var result = new AtomicReference<ChatResponse>();
            var error = new AtomicReference<Throwable>();
            wrapped.stream("Continue the nuclei-counting task").onPartialResponse(t -> {})
                .onCompleteResponse(r -> { result.set(r); done.countDown(); })
                .onError(e -> {error.set(e); done.countDown();}).start();
            check(done.await(5,TimeUnit.SECONDS), "empty Responses reply must end visibly");
            check(error.get()==null && result.get()!=null && result.get().aiMessage().text()!=null &&
                result.get().aiMessage().text().contains("Task paused"),
                "real empty/incomplete Responses event must become a paused checkpoint");
            check(memory.messages().stream().noneMatch(m -> m instanceof AiMessage a &&
                !a.hasToolExecutionRequests() && (a.text()==null || a.text().isBlank())),
                "empty assistant placeholder must not remain in continuation memory");
        } finally {server.stop(0);}
    }
}
