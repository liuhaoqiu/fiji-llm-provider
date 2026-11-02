/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import sc.fiji.llm.provider.LLMProvider.VisionSupport;

/** Exercises actual HTTP discovery and persistent fallback without any real key. */
public final class ModelCatalogVerificationMain {
    private static final String INITIAL = """
        {"object":"list","data":[
          {"id":"future-vision-9000","name":"Future Vision","input_modalities":["text","image"],
           "context_window":1048576,"max_output_tokens":393216,
           "effort":{"supported_levels":["low","high"],"default_level":"low"}},
          {"id":"future-text-9000","input_modalities":["text"]},
          {"id":"future-unspecified-9000"},
          {"id":"future-text-9000","input_modalities":["text"]}
        ]}
        """;

    public static void main(String[] args) throws Exception {
        AtomicReference<String> body = new AtomicReference<>(INITIAL);
        AtomicInteger code = new AtomicInteger(200);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> serverError = new AtomicReference<>();
        AtomicInteger delayMs = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod()) ||
                !"Bearer catalog-test-key".equals(exchange.getRequestHeaders().getFirst("Authorization")))
                serverError.set("Wrong method or authorization header");
            requests.incrementAndGet();
            try { if (delayMs.get() > 0) Thread.sleep(delayMs.get()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] data = body.get().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code.get(), data.length);
                exchange.getResponseBody().write(data);
            } finally { exchange.close(); }
        });
        server.start();
        Path directory = Files.createTempDirectory("deepseek-catalog-test-");
        Path cache = directory.resolve("models.json");
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        AtomicLong now = new AtomicLong(1_000_000);
        try {
            var catalog = new DeepSeekModelCatalog(baseUrl, cache, Duration.ofSeconds(2), now::get);
            check(catalog.refresh(null, false).isEmpty() && requests.get() == 0, "no key, no network, no fake defaults");
            var ids = catalog.refresh("catalog-test-key", false);
            check(ids.equals(List.of("future-vision-9000", "future-text-9000", "future-unspecified-9000")),
                "arbitrary new model IDs and duplicate removal");
            check(catalog.model(ids.get(0)).vision() == VisionSupport.SUPPORTED, "dynamic image support");
            check(catalog.model(ids.get(1)).vision() == VisionSupport.UNSUPPORTED, "dynamic text-only support");
            check(catalog.model(ids.get(2)).vision() == VisionSupport.UNKNOWN, "missing metadata stays unknown");
            check(catalog.model(ids.get(0)).memoryTokenLimit() == 491520, "dynamic context limit");
            check("low".equals(catalog.model(ids.get(0)).reasoningEffort()), "dynamic default reasoning effort");
            check(!Files.readString(cache).contains("catalog-test-key"), "no key in cache");
            int count = requests.get();
            catalog.refresh("catalog-test-key", false);
            check(requests.get() == count, "coalesced refresh within 60 seconds");

            var provider = new DeepSeekProvider() {
                @Override protected String baseUrl() { return baseUrl; }
                @Override protected String apiKey() { return "catalog-test-key"; }
                @Override protected Path modelCacheFile() { return cache; }
                @Override protected List<dev.langchain4j.model.chat.listener.ChatModelListener> listeners() {
                    return List.of();
                }
            };
            check(provider.getAvailableModels().equals(ids), "provider uses HTTP list");
            check(provider.validateModel("future-vision-9000").isPresent(), "new model accepted");
            check(provider.validateModel("deepseek-flash").isEmpty(), "old hardcoded ID not accepted");
            check(provider.supportsImageToolResults("future-vision-9000"), "new image model usable");
            check("low".equals(((OpenAiResponsesChatRequestParameters) provider
                .defaultChatRequestParameters("future-vision-9000")).reasoningEffort()), "provider metadata wired");
            check(provider.createChatModel("future-vision-9000") != null, "new model builder accepts dynamic ID");

            var locked = new java.util.concurrent.atomic.AtomicBoolean(true);
            var authenticationAccepted = new java.util.concurrent.atomic.AtomicBoolean(true);
            var cancelSelection = new java.util.concurrent.atomic.AtomicBoolean(false);
            var statuses = new AtomicInteger();
            var setupProvider = new DeepSeekProvider() {
                @Override protected String baseUrl() { return baseUrl; }
                @Override protected String apiKey() {
                    if (locked.get()) throw new IllegalStateException("test vault locked");
                    return "catalog-test-key";
                }
                @Override protected Path modelCacheFile() { return null; }
                @Override protected boolean ensureApiKeyAvailable() {
                    if (!authenticationAccepted.get()) return false;
                    locked.set(false); return true;
                }
                @Override protected String chooseModel(List<String> models) {
                    return cancelSelection.get() ? null : models.get(0);
                }
                @Override protected void showCatalogStatus() { statuses.incrementAndGet(); }
            };
            check(setupProvider.getAvailableModels().equals(List.of(DeepSeekProvider.LOAD_MODELS)), "locked startup has setup action");
            check(setupProvider.validateModel(DeepSeekProvider.LOAD_MODELS).orElseThrow().modelName()
                .equals("future-vision-9000"), "unlock then fetch and select dynamic model");
            cancelSelection.set(true);
            check(setupProvider.validateModel(DeepSeekProvider.LOAD_MODELS).isEmpty(), "model chooser cancel exits");
            authenticationAccepted.set(false);
            check(setupProvider.validateModel(DeepSeekProvider.LOAD_MODELS).isEmpty(), "authentication cancel exits");
            authenticationAccepted.set(true);
            body.set("{\"data\":[]}");
            check(setupProvider.validateModel(DeepSeekProvider.LOAD_MODELS).isEmpty() && statuses.get() == 1,
                "empty server list shows one status and exits");

            body.set("{\"data\":[{\"id\":\"renamed-next-generation\",\"input_modalities\":[\"text\"]}]}");
            now.addAndGet(60_001);
            check(catalog.refresh("catalog-test-key", false).equals(List.of("renamed-next-generation")),
                "TTL refresh replaces removed models");
            check(catalog.model("future-vision-9000") == null, "removed capabilities purged");
            check(provider.refreshAvailableModels().equals(List.of("renamed-next-generation")), "manual refresh bypasses TTL");
            check(provider.validateModel("future-vision-9000").isEmpty(), "retired model rejected");
            try {
                provider.createStreamingChatModel("future-vision-9000");
                throw new AssertionError("Retired model builder accepted");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("Reopen model settings"), "actionable retired model error");
            }

            code.set(503);
            check(catalog.refresh("catalog-test-key", true).equals(List.of("renamed-next-generation")),
                "HTTP failure retains last successful list");
            check(catalog.status().contains("out of date") && catalog.status().contains("503"), "stale status visible");
            var restart = new DeepSeekModelCatalog(baseUrl, cache);
            check(restart.refresh(null, false).equals(List.of("renamed-next-generation")), "restart while vault locked uses cache");
            code.set(401);
            restart.refresh("catalog-test-key", true);
            check(restart.status().contains("401"), "authentication failure explicit");
            code.set(200);
            body.set("{broken response");
            check(restart.refresh("catalog-test-key", true).equals(List.of("renamed-next-generation")), "invalid response retains cache");
            body.set("{\"data\":[]}");
            check(restart.refresh("catalog-test-key", true).isEmpty(), "valid empty list clears all retired models");
            check(new DeepSeekModelCatalog(baseUrl, cache).ids().isEmpty(), "empty successful catalog persists");

            Files.writeString(cache, "bad cache");
            var corrupt = new DeepSeekModelCatalog(baseUrl, cache);
            check(corrupt.refresh(null, false).isEmpty(), "corrupt cache handled");
            body.set(INITIAL);
            check(!corrupt.refresh("catalog-test-key", false).isEmpty(), "unlock immediately refreshes after missing key");
            var timeout = new DeepSeekModelCatalog(baseUrl, cache, Duration.ofMillis(150), now::get);
            delayMs.set(600);
            check(!timeout.refresh("catalog-test-key", true).isEmpty(), "timeout uses disk cache");
            check(timeout.status().contains("failed"), "timeout status");
            check(serverError.get() == null, "HTTP method and auth checks");
            System.out.println("PASS: dynamic IDs, removals, metadata, TTL, forced refresh, persistence, auth, malformed/empty responses and timeout");
        } finally {
            server.stop(0);
            Files.deleteIfExists(cache);
            Files.deleteIfExists(directory);
        }
    }

    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
