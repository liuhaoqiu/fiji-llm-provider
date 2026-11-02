/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import sc.fiji.llm.provider.LLMProvider.VisionSupport;

/** Real delayed-header, keep-alive and upstream-failure checks for all providers. */
public final class ProviderTimeoutVerificationMain {
    private static final String MODEL = "fixture-slow-model";
    private static final Duration DELAY = Duration.ofSeconds(65);
    private static final AtomicInteger requests = new AtomicInteger();
    private static final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private static final Map<AbstractCatalogProvider, HttpClientBuilder> builders = new IdentityHashMap<>();
    private static String origin;

    private static HttpClientBuilder capture(AbstractCatalogProvider provider, HttpClientBuilder builder) {
        builders.put(provider, builder);
        return builder;
    }

    private static String key(String name) { return "synthetic-timeout-key-" + name; }

    private static final class TestDeepSeek extends DeepSeekProvider {
        @Override protected String baseUrl() { return origin + "/deepseek"; }
        @Override protected String apiKey() { return key(getName()); }
        @Override protected Path modelCacheFile() { return null; }
        @Override protected List<ChatModelListener> listeners() { return List.of(); }
        @Override protected HttpClientBuilder httpClientBuilder() { return capture(this, super.httpClientBuilder()); }
    }

    private static final class TestSiliconFlow extends SiliconFlowProvider {
        @Override protected String baseUrl() { return origin + "/siliconflow/v1"; }
        @Override protected String apiKey() { return key(getName()); }
        @Override protected Path modelCacheFile() { return null; }
        @Override protected List<ChatModelListener> listeners() { return List.of(); }
        @Override protected HttpClientBuilder httpClientBuilder() { return capture(this, super.httpClientBuilder()); }
    }

    private static final class TestNvidia extends NvidiaProvider {
        @Override protected String baseUrl() { return origin + "/nvidia/v1"; }
        @Override protected String apiKey() { return key(getName()); }
        @Override protected Path modelCacheFile() { return null; }
        @Override protected List<ChatModelListener> listeners() { return List.of(); }
        @Override protected HttpClientBuilder httpClientBuilder() { return capture(this, super.httpClientBuilder()); }
    }

    private static final class TestArk extends VolcengineProvider {
        @Override protected String baseUrl() { return origin + "/volcengine/api/v3"; }
        @Override protected String apiKey() { return key(getName()); }
        @Override protected Path modelCacheFile() { return null; }
        @Override protected List<ChatModelListener> listeners() { return List.of(); }
        @Override protected HttpClientBuilder httpClientBuilder() { return capture(this, super.httpClientBuilder()); }
    }

    public static void main(String[] args) throws Exception {
        verifyNestedMetadata();
        var timer = Executors.newScheduledThreadPool(8);
        var worker = Executors.newFixedThreadPool(4);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                boolean responses = path.startsWith("/deepseek/");
                String service = responses ? "DeepSeek" : path.startsWith("/siliconflow/") ? "SiliconFlow" : path.startsWith("/nvidia/") ? "NVIDIA" : "Volcengine";
                check(("Bearer " + key(service)).equals(exchange.getRequestHeaders().getFirst("Authorization")), "separate synthetic auth");
                if (path.endsWith("/models")) {
                    check("GET".equals(exchange.getRequestMethod()), "catalog method");
                    sendJson(exchange, 200, "{\"data\":[{\"id\":\"" + MODEL + "\"}]}");
                    return;
                }
                check("POST".equals(exchange.getRequestMethod()), "chat method");
                check(path.endsWith(responses ? "/responses" : "/chat/completions"), "correct protocol path");
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var request = JsonParser.parseString(raw).getAsJsonObject();
                boolean streaming = request.has("stream") && request.get("stream").getAsBoolean();
                boolean legacyChat = !responses && "legacy".equals(request.get("model").getAsString());
                requests.incrementAndGet();
                if (raw.contains("upstream-timeout")) {
                    sendJson(exchange, 504, "{\"error\":{\"message\":\"fixture gateway timeout\",\"type\":\"gateway_timeout\"}}");
                    return;
                }
                if (streaming && raw.contains("keepalive probe")) {
                    keepAliveThenRespond(exchange, responses, timer);
                    return;
                }
                // Hold headers as a real queued/reasoning server can do.
                timer.schedule(() -> {
                    try { respond(exchange, streaming, responses); }
                    catch (IOException closed) { if (!legacyChat) serverFailure.compareAndSet(null, closed); }
                    catch (Throwable failure) { serverFailure.compareAndSet(null, failure); }
                }, DELAY.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Throwable failure) { serverFailure.compareAndSet(null, failure); exchange.close(); }
        });
        server.start();
        try {
            var legacyOutcome = new CompletableFuture<ChatResponse>();
            var legacyResponsesOutcome = new CompletableFuture<ChatResponse>();
            long started = System.nanoTime();
            OpenAiStreamingChatModel.builder().baseUrl(origin + "/siliconflow/v1").apiKey(key("SiliconFlow"))
                .modelName("legacy").build().chat("delayed response", handler(legacyOutcome));
            var oldResponsesBuilder = new JdkHttpClientBuilder();
            OpenAiResponsesStreamingChatModel.builder().baseUrl(origin + "/deepseek").apiKey(key("DeepSeek"))
                .httpClientBuilder(oldResponsesBuilder).modelName("legacy").build()
                .chat("delayed response", handler(legacyResponsesOutcome));
            check(oldResponsesBuilder.readTimeout() == null && oldResponsesBuilder.connectTimeout() == null,
                "original Responses client does not install Chat Completions' 60-second default");

            var providers = List.of(new TestSiliconFlow(), new TestDeepSeek(), new TestArk(), new TestNvidia());
            var outcomes = new ArrayList<CompletableFuture<ChatResponse>>();
            var labels = new ArrayList<String>();
            for (var provider : providers) {
                var sync = provider.createChatModel(MODEL);
                checkTimeouts(builders.get(provider), provider.getName() + " sync");
                outcomes.add(CompletableFuture.supplyAsync(() -> sync.chat(ChatRequest.builder()
                    .messages(UserMessage.from("delayed response")).build()), worker));
                labels.add(provider.getName() + " delayed synchronous");
                for (String prompt : List.of("delayed response", "keepalive probe")) {
                    var stream = provider.createStreamingChatModel(MODEL);
                    checkTimeouts(builders.get(provider), provider.getName() + " stream");
                    var outcome = new CompletableFuture<ChatResponse>();
                    stream.chat(prompt, handler(outcome));
                    outcomes.add(outcome);
                    labels.add(provider.getName() + " streaming " + prompt);
                }
            }
            System.out.println("RUNNING: all four providers, real 65-second header delay and SSE keep-alives; original Chat/Responses controls");
            try {
                legacyOutcome.get(80, TimeUnit.SECONDS);
                throw new AssertionError("Default client unexpectedly survived the 65-second header delay");
            } catch (ExecutionException expected) {
                check(isTimeout(expected), "original timeout exception reproduced");
                System.out.println("REPRODUCED: original Chat Completions client timed out after " + Duration.ofNanos(System.nanoTime() - started).toSeconds() + " seconds");
            }
            check("late-ok".equals(legacyResponsesOutcome.get(20, TimeUnit.SECONDS).aiMessage().text()), "original Responses client survives 65 seconds");
            System.out.println("CONFIRMED: original Responses client has no configured 60-second limit and completed the delayed response");
            for (int i = 0; i < outcomes.size(); i++) {
                check("late-ok".equals(outcomes.get(i).get(20, TimeUnit.SECONDS).aiMessage().text()), labels.get(i));
                System.out.println("PASS: " + labels.get(i));
            }
            check(requests.get() == 14, "one request per case, no repeated generation");
            for (var provider : List.of(providers.get(0), providers.get(1), providers.get(3))) {
                var failure = new CompletableFuture<ChatResponse>();
                provider.createStreamingChatModel(MODEL).chat("upstream-timeout", handler(failure));
                try { failure.get(5, TimeUnit.SECONDS); throw new AssertionError("Upstream 504 was hidden"); }
                catch (ExecutionException expected) { check(expected.toString().contains("504") || expected.toString().contains("fixture gateway timeout"), "upstream gateway failure remains visible"); }
            }
            check(requests.get() == 17, "upstream failures do not silently replay requests");
            check(serverFailure.get() == null, "server failure: " + serverFailure.get());
            System.out.println("PASS: 30-minute response/15-second connection settings for all eight model factories, upstream 504 visibility and Ark metadata regression");
        } finally { server.stop(0); timer.shutdownNow(); worker.shutdownNow(); }
    }

    private static void checkTimeouts(HttpClientBuilder builder, String label) {
        check(builder.readTimeout().equals(Duration.ofMinutes(30)), label + " effective response timeout");
        check(builder.connectTimeout().equals(Duration.ofSeconds(15)), label + " independent connect timeout");
    }

    private static void verifyNestedMetadata() {
        // Schema observed in the user's authenticated Ark /models cache, 2026-11-02.
        String json = "{\"data\":[{\"id\":\"text-declaration\",\"domain\":\"LLM\",\"modalities\":{\"input_modalities\":[\"text\"]}," +
            "\"token_limits\":{\"context_window\":1048576,\"max_input_token_length\":1048576,\"max_output_token_length\":393216}}," +
            "{\"id\":\"vision-declaration\",\"domain\":\"VLM\",\"modalities\":{\"input_modalities\":[\"text\",\"image\",\"video\"]}," +
            "\"token_limits\":{\"context_window\":262144,\"max_output_token_length\":32768}}]}";
        var catalog = DeepSeekModelCatalog.parseModels(JsonParser.parseString(json).getAsJsonObject(), "Volcengine");
        var text = catalog.get("text-declaration");
        check(text.contextWindow() == 1048576 && text.maxOutputTokens() == 393216, "nested token limits parsed");
        check(text.memoryTokenLimit() == 491520, "metadata drives context budget");
        check(text.vision() == VisionSupport.UNKNOWN, "Ark text-only listing is not definitive negative vision evidence");
        check(catalog.get("vision-declaration").vision() == VisionSupport.SUPPORTED, "nested image declaration recognized");
        var deepseek = DeepSeekModelCatalog.parseModels(JsonParser.parseString("{\"data\":[{\"id\":\"text\",\"input_modalities\":[\"text\"]}]}").getAsJsonObject());
        check(deepseek.get("text").vision() == VisionSupport.UNSUPPORTED, "DeepSeek metadata contract unchanged");
    }

    private static StreamingChatResponseHandler handler(CompletableFuture<ChatResponse> outcome) {
        return new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String text) {}
            @Override public void onCompleteResponse(ChatResponse response) { outcome.complete(response); }
            @Override public void onError(Throwable error) { outcome.completeExceptionally(error); }
        };
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause.getClass().getSimpleName().contains("Timeout") || String.valueOf(cause.getMessage()).contains("request timed out")) return true;
        return false;
    }

    private static String payload(boolean streaming, boolean responses) {
        if (responses) {
            String response = "{\"id\":\"late\",\"object\":\"response\",\"model\":\"" + MODEL + "\",\"status\":\"completed\",\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"late-ok\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}";
            return streaming ? "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":0,\"item_id\":\"msg_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"late-ok\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"sequence_number\":1,\"response\":" + response + "}\n\n" : response;
        }
        return streaming ?
            "data: {\"id\":\"late\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"late-ok\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"late\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n" :
            "{\"id\":\"late\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"late-ok\"},\"finish_reason\":\"stop\"}]}";
    }

    private static void respond(HttpExchange exchange, boolean streaming, boolean responses) throws IOException {
        byte[] bytes = payload(streaming, responses).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private static void keepAliveThenRespond(HttpExchange exchange, boolean responses, ScheduledExecutorService timer) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        var output = exchange.getResponseBody();
        var heartbeat = timer.scheduleAtFixedRate(() -> {
            synchronized (output) {
                try { output.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8)); output.flush(); }
                catch (IOException failed) { serverFailure.compareAndSet(null, failed); }
            }
        }, 0, 10, TimeUnit.SECONDS);
        timer.schedule(() -> {
            heartbeat.cancel(false);
            synchronized (output) {
                try (output) { output.write(payload(true, responses).getBytes(StandardCharsets.UTF_8)); }
                catch (IOException failed) { serverFailure.compareAndSet(null, failed); }
            }
        }, DELAY.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
