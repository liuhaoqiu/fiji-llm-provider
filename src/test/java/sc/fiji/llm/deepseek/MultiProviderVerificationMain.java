/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.scijava.prefs.PrefService;
import sc.fiji.llm.chat.ImageToolResultNormalizer;
import sc.fiji.llm.provider.LLMProvider.VisionSupport;

/** Local wire tests for the three Chat Completions services; no paid calls. */
public final class MultiProviderVerificationMain {
    private static String origin;
    private static String mode = "text";
    private static int catalogStatus = 200;
    private static String modelId = "vendor/new-chat-2040";
    private static final AtomicReference<JsonObject> lastRequest = new AtomicReference<>();
    private static final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5xkAAAAASUVORK5CYII=";

    private static class TestSilicon extends SiliconFlowProvider {
        boolean unlocked = true;
        @Override protected String baseUrl() { return origin + "/v1"; }
        @Override protected String apiKey() { return unlocked ? "synthetic-silicon-key" : null; }
        @Override protected Path modelCacheFile() { return null; }
        @Override protected List<ChatModelListener> listeners() { return List.of(); }
    }
    private static class TestArk extends VolcengineProvider {
        String entered;
        PrefService prefs;
        @Override protected String baseUrl() { return origin + "/api/v3"; }
        @Override protected String apiKey() { return "synthetic-ark-key"; }
        @Override protected Path modelCacheFile() { return null; }
        @Override protected List<ChatModelListener> listeners() { return List.of(); }
        @Override protected String promptCustomModel(String previous) { return entered; }
        @Override protected PrefService preferences() { return prefs; }
    }
    private static class TestNvidia extends NvidiaProvider {
        boolean unlocked = true;
        @Override protected String baseUrl() { return origin + "/nvidia/v1"; }
        @Override protected String apiKey() { return unlocked ? "synthetic-nvidia-key" : null; }
        @Override protected Path modelCacheFile() { return null; }
        @Override protected List<ChatModelListener> listeners() { return List.of(); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            var root = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
            var parsed = DeepSeekModelCatalog.parseModels(root, "NVIDIA");
            check(!parsed.isEmpty() && parsed.size() == root.getAsJsonArray("data").size(), "real NVIDIA public catalog parsed without inventing/removing IDs");
            for (var entry : root.getAsJsonArray("data")) {
                var item = entry.getAsJsonObject();
                check(parsed.containsKey(item.get("id").getAsString()), "public model ID preserved");
                if (!item.has("input_modalities") && !item.has("modalities"))
                    check(parsed.get(item.get("id").getAsString()).vision() == VisionSupport.UNKNOWN, "public catalog missing capabilities stay unknown");
            }
            System.out.println("PASS: real public NVIDIA catalog parsed (" + parsed.size() + " model IDs); no live inference performed");
            return;
        }
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", MultiProviderVerificationMain::handle);
        server.start();
        try {
            for (var provider : List.of(new TestSilicon(), new TestArk(), new TestNvidia())) {
                modelId = "vendor/new-chat-2040";
                catalogStatus = 200;
                check(provider.refreshAvailableModels().equals(List.of(modelId)), provider.getName() + " catalog and filtering");
                check(provider.getVisionSupport(modelId) == VisionSupport.SUPPORTED, "explicit vision metadata");
                check(!provider.supportsImageToolResults(modelId), "Chat tool images require normalization");
                verifyTransport(provider);
                modelId = "vendor/replacement-2041";
                check(provider.refreshAvailableModels().equals(List.of(modelId)), "new server IDs replace removed IDs");
                check(provider.validateModel("vendor/new-chat-2040").isEmpty(), "removed catalog model rejected");
                catalogStatus = 503;
                check(provider.refreshAvailableModels().equals(List.of(modelId)), "temporary failure retains last list");
                check(provider.getModelCatalogStatus().contains("may be out of date"), "stale status visible");
            }
            catalogStatus = 200;
            verifySetupAndCustomEndpoint();
            verifyCachesAndUrls();
            check(serverFailure.get() == null, "server assertion: " + serverFailure.get());
            System.out.println("PASS: SiliconFlow, Volcengine and NVIDIA catalogs, auth isolation, sync/SSE, reasoning tool loops, image payloads, failures, cache isolation and manual Endpoint persistence");
        } finally { server.stop(0); }
    }

    private static void verifyTransport(ChatCompletionsProvider provider) throws Exception {
        mode = "text";
        var model = provider.createChatModel(modelId);
        check("pong".equals(model.chat("ping")), "sync Chat Completions");
        check(!lastRequest.get().has("input"), "not a Responses request");
        check(!lastRequest.get().has("temperature") && !lastRequest.get().has("reasoning_effort"), "server defaults preserved");

        mode = "stream";
        var streamed = stream(provider, ChatRequest.builder().messages(UserMessage.from("stream")).build());
        check("stream-ok".equals(streamed.aiMessage().text()), "SSE final text");

        var tool = ToolSpecification.builder().name("inspect_image").description("Synthetic Fiji image tool")
            .parameters(JsonObjectSchema.builder().addStringProperty("title").required("title").build()).build();
        var user = UserMessage.from("Inspect my image");
        var request = ChatRequest.builder().messages(user).toolSpecifications(tool).build();
        mode = "tool";
        var first = model.chat(request).aiMessage();
        check(first.hasToolExecutionRequests() && first.toolExecutionRequests().get(0).name().equals("inspect_image"), "tool call parsed");
        check("inspect first".equals(first.thinking()), "reasoning_content captured");
        mode = "stream-tool";
        var streamedTool = stream(provider, request).aiMessage();
        check(streamedTool.hasToolExecutionRequests() && "call_1".equals(streamedTool.toolExecutionRequests().get(0).id()), "SSE tool id");
        check("inspect first".equals(streamedTool.thinking()), "SSE reasoning captured");

        var result = ToolExecutionResultMessage.from(first.toolExecutionRequests().get(0), "image metadata");
        mode = "tool-result";
        check("done".equals(model.chat(ChatRequest.builder().messages(user, first, result).toolSpecifications(tool).build()).aiMessage().text()), "tool round trip");
        var assistantOnWire = lastRequest.get().getAsJsonArray("messages").get(1).getAsJsonObject();
        check("inspect first".equals(assistantOnWire.get("reasoning_content").getAsString()), "reasoning_content returned on next tool turn");
        var resultOnWire = lastRequest.get().getAsJsonArray("messages").get(2).getAsJsonObject();
        check("call_1".equals(resultOnWire.get("tool_call_id").getAsString()), "matching tool result id");

        var imageResult = ToolExecutionResultMessage.builder().id("call_1").toolName("inspect_image")
            .contents(List.of(TextContent.from("image metadata"), ImageContent.from(PNG, "image/png"))).build();
        var memory = provider.createTokenChatMemory(modelId);
        memory.add(user); memory.add(first); memory.add(imageResult);
        // This is the installed Fiji core's production normalization path.
        var normalized = ImageToolResultNormalizer.normalize(ChatRequest.builder().messages(memory.messages()).toolSpecifications(tool).build());
        model.chat(normalized);
        var messages = lastRequest.get().getAsJsonArray("messages");
        check("tool".equals(messages.get(2).getAsJsonObject().get("role").getAsString()), "tool result retained");
        check(messages.get(2).getAsJsonObject().get("content").isJsonPrimitive(), "tool result is a string");
        var imageUser = messages.get(3).getAsJsonObject();
        check("user".equals(imageUser.get("role").getAsString()), "image follows tools as a user message");
        check(("data:image/png;base64," + PNG).equals(imageUser.getAsJsonArray("content").get(1)
            .getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString()), "image bytes preserved");
        stream(provider, normalized);

        mode = "unauthorized";
        try { model.chat("invalid key"); throw new AssertionError("401 was ignored"); }
        catch (RuntimeException expected) {
            String message = String.valueOf(expected.getMessage());
            check(message.contains("401") || message.contains("invalid_api_key") || message.contains("synthetic unauthorized"), "401 authentication failure reported: " + message);
        }
        if (provider instanceof NvidiaProvider) {
            mode = "forbidden";
            try { model.chat("catalog visible but inference not permitted"); throw new AssertionError("403 was ignored"); }
            catch (RuntimeException expected) {
                String message = String.valueOf(expected.getMessage());
                check(message.contains("403") || message.contains("fixture authorization failed"), "inference permission error is visible even with a valid model catalog");
            }
        }
        mode = "text";
    }

    private static ChatResponse stream(ChatCompletionsProvider provider, ChatRequest request) throws Exception {
        var done = new CountDownLatch(1);
        var response = new AtomicReference<ChatResponse>();
        var failure = new AtomicReference<Throwable>();
        provider.createStreamingChatModel(modelId).chat(request, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String part) {}
            @Override public void onCompleteResponse(ChatResponse value) { response.set(value); done.countDown(); }
            @Override public void onError(Throwable error) { failure.set(error); done.countDown(); }
        });
        check(done.await(10, TimeUnit.SECONDS), "stream timeout");
        check(failure.get() == null && response.get() != null, "stream error: " + failure.get());
        return response.get();
    }

    private static void verifySetupAndCustomEndpoint() {
        var locked = new TestSilicon(); locked.unlocked = false;
        check(locked.getAvailableModels().equals(List.of("<Unlock / refresh SiliconFlow models...>")), "service-specific setup choice");
        check(locked.validateModel(locked.getAvailableModels().get(0)).isEmpty(), "locked setup cancels cleanly");
        var nvidia = new TestNvidia(); nvidia.unlocked = false;
        check(nvidia.getAvailableModels().equals(List.of("<Unlock / refresh NVIDIA models...>")), "NVIDIA missing-key setup");
        check(nvidia.validateModel(nvidia.getAvailableModels().get(0)).isEmpty(), "NVIDIA setup cancellation");
        check(nvidia.getModelCatalogStatus().contains("non-chat models"), "NVIDIA catalog scope explained");
        var values = new java.util.HashMap<String, String>();
        var prefs = (PrefService) java.lang.reflect.Proxy.newProxyInstance(PrefService.class.getClassLoader(), new Class<?>[]{PrefService.class}, (p,m,a) -> {
            if (m.getName().equals("getInt")) return Integer.parseInt(values.getOrDefault((String)a[1], String.valueOf(a[2])));
            if (m.getName().equals("get")) return values.getOrDefault((String) a[1], (String) a[2]);
            if (m.getName().equals("put")) values.put((String) a[1], String.valueOf(a[2]));
            return null;
        });
        var ark = new TestArk(); ark.prefs = prefs;
        ark.entered = null;
        check(ark.validateModel(VolcengineProvider.CUSTOM_MODEL).isEmpty(), "manual entry cancel");
        ark.entered = "https://wrong.example/key";
        check(ark.validateModel(VolcengineProvider.CUSTOM_MODEL).isEmpty(), "a URL is not a model ID");
        ark.entered = " ep-local-fixture ";
        check(ark.validateModel(VolcengineProvider.CUSTOM_MODEL).orElseThrow().modelName().equals("ep-local-fixture"), "manual endpoint accepted");
        var restarted = new TestArk(); restarted.prefs = prefs;
        check(restarted.getAvailableModels().contains("ep-local-fixture"), "manual endpoint survives restart");
        check(restarted.validateModel("ep-older-conversation").isPresent(), "old endpoint history remains usable");
        mode = "text";
        check("pong".equals(restarted.createChatModel("ep-local-fixture").chat("ping")), "manual endpoint request");
        check("ep-local-fixture".equals(lastRequest.get().get("model").getAsString()), "endpoint unchanged on wire");
        try { restarted.createChatModel(VolcengineProvider.CUSTOM_MODEL); throw new AssertionError("sentinel reached model"); }
        catch (IllegalArgumentException expected) { /* setup actions cannot be sent as model IDs */ }
    }

    private static void verifyCachesAndUrls() throws Exception {
        var sf = new SiliconFlowProvider(); var ark = new VolcengineProvider(); var ds = new DeepSeekProvider(); var nv = new NvidiaProvider();
        check(sf.modelsUrl().equals("https://api.siliconflow.cn/v1/models?sub_type=chat"), "official SiliconFlow chat catalog URL");
        check(ark.modelsUrl().equals("https://ark.cn-beijing.volces.com/api/v3/models"), "official Ark origin/path");
        check(nv.modelsUrl().equals("https://integrate.api.nvidia.com/v1/models"), "official NVIDIA origin/version/path");
        check(ds.modelCacheFile().getFileName().toString().equals("deepseek-models-v1.json"), "existing DeepSeek cache location preserved");
        check(java.util.Set.of(sf.modelCacheFile(), ark.modelCacheFile(), ds.modelCacheFile(), nv.modelCacheFile()).size() == 4, "four separate cache paths");
        check(nv.modelCacheFile().getFileName().toString().equals("nvidia-models-v1.json"), "NVIDIA cache file name");
        var temp = Files.createTempDirectory("fiji-llm-providers-test-");
        try {
            Path cache = temp.resolve("catalog.json");
            var catalog = new DeepSeekModelCatalog("SiliconFlow", origin + "/v1/models?sub_type=chat", cache);
            check(catalog.refresh("synthetic-silicon-key", true).size() == 1, "disk cache saved");
            check(!Files.readString(cache).contains("synthetic-silicon-key"), "cache has no key");
            var different = new DeepSeekModelCatalog("Volcengine", origin + "/api/v3/models", cache);
            check(different.ids().isEmpty(), "different source cache rejected");
            Files.delete(cache);
        } finally { Files.delete(temp); }
        var unknown = DeepSeekModelCatalog.parseModels(JsonParser.parseString("{\"data\":[{\"id\":\"model-without-metadata\"}]}").getAsJsonObject(), "SiliconFlow");
        check(unknown.values().iterator().next().vision() == VisionSupport.UNKNOWN, "missing metadata is unknown");
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            boolean silicon = exchange.getRequestURI().getPath().startsWith("/v1/");
            boolean nvidia = exchange.getRequestURI().getPath().startsWith("/nvidia/v1/");
            String prefix = silicon ? "/v1" : nvidia ? "/nvidia/v1" : "/api/v3";
            String key = silicon ? "synthetic-silicon-key" : nvidia ? "synthetic-nvidia-key" : "synthetic-ark-key";
            check(("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization")), "provider key never crosses service");
            if (exchange.getRequestURI().getPath().equals(prefix + "/models")) {
                check(exchange.getRequestMethod().equals("GET"), "model discovery GET");
                check(silicon ? "sub_type=chat".equals(exchange.getRequestURI().getQuery()) : exchange.getRequestURI().getQuery() == null, "provider catalog query");
                String extra = silicon || nvidia ? "" : ",{\"id\":\"fixture-embedding\",\"domain\":\"Embedding\"},{\"id\":\"fixture-retired\",\"domain\":\"LLM\",\"status\":\"Shutdown\"}";
                send(exchange, catalogStatus, "application/json", "{\"data\":[{\"id\":\"" + modelId + "\",\"domain\":\"VLM\",\"input_modalities\":[\"text\",\"image\"],\"context_window\":131072}" + extra + "]}");
                return;
            }
            check(exchange.getRequestURI().getPath().equals(prefix + "/chat/completions"), "Chat Completions route");
            check(exchange.getRequestMethod().equals("POST"), "chat POST");
            var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            lastRequest.set(body);
            if (mode.equals("unauthorized")) { send(exchange, 401, "application/json", "{\"error\":{\"message\":\"synthetic unauthorized\",\"type\":\"invalid_api_key\"}}"); return; }
            if (mode.equals("forbidden")) { send(exchange, 403, "application/json", "{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"fixture authorization failed\"}"); return; }
            boolean tool = mode.equals("tool") || mode.equals("stream-tool");
            String toolCall = "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"inspect_image\",\"arguments\":\"{\\\"title\\\":\\\"test\\\"}\"}}";
            boolean streaming = body.has("stream") && body.get("stream").getAsBoolean();
            if (streaming) {
                String delta = tool ? "\"reasoning_content\":\"inspect first\",\"tool_calls\":[" + toolCall.replace("{\"id\"", "{\"index\":0,\"id\"") + "]" : "\"content\":\"stream-ok\"";
                String events = "data: {\"id\":\"fixture-stream\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"," + delta + "},\"finish_reason\":null}]}\n\n" +
                    "data: {\"id\":\"fixture-stream\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + (tool ? "tool_calls" : "stop") + "\"}]}\n\ndata: [DONE]\n\n";
                send(exchange, 200, "text/event-stream", events);
            } else {
                String content = tool ? "\"content\":null,\"reasoning_content\":\"inspect first\",\"tool_calls\":[" + toolCall + "]" : "\"content\":\"" + (mode.equals("tool-result") ? "done" : "pong") + "\"";
                send(exchange, 200, "application/json", "{\"id\":\"fixture-chat\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," + content + "},\"finish_reason\":\"" + (tool ? "tool_calls" : "stop") + "\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}");
            }
        } catch (Throwable error) {
            serverFailure.compareAndSet(null, error);
            send(exchange, 400, "application/json", "{\"error\":{\"message\":\"test server assertion failed\"}}");
        }
    }
    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
