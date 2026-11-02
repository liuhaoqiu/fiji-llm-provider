/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import sc.fiji.llm.provider.LLMProvider.VisionSupport;

/** Authenticated model discovery. Only successful metadata responses are cached. */
final class DeepSeekModelCatalog {
    static final long REFRESH_INTERVAL_MS = 60_000;
    private static final int MAX_DOCUMENT_BYTES = 1_048_576;

    record Model(String id, String name, VisionSupport vision,
        Long contextWindow, Long maxOutputTokens, String reasoningEffort) {
        int memoryTokenLimit() {
            if (contextWindow == null) return 32_768;
            long reserved = maxOutputTokens == null ? contextWindow / 4 : maxOutputTokens;
            long inputBudget = contextWindow - reserved;
            if (inputBudget <= 0) inputBudget = contextWindow / 2;
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE / 2L, inputBudget * 3 / 4));
        }
    }

    private final URI endpoint;
    private final String providerName;
    private final Path cacheFile;
    private final Duration timeout;
    private final LongSupplier clock;
    private final HttpClient client;
    private Map<String, Model> models = Map.of();
    private boolean hasSnapshot;
    private long lastAttempt = Long.MIN_VALUE;
    private String status;

    DeepSeekModelCatalog(String baseUrl, Path cacheFile) {
        this(baseUrl, cacheFile, Duration.ofSeconds(5), System::currentTimeMillis);
    }

    DeepSeekModelCatalog(String baseUrl, Path cacheFile, Duration timeout, LongSupplier clock) {
        this("DeepSeek", baseUrl.replaceAll("/+$", "") + "/models", cacheFile, timeout, clock);
    }

    DeepSeekModelCatalog(String providerName, String modelsUrl, Path cacheFile) {
        this(providerName, modelsUrl, cacheFile, Duration.ofSeconds(5), System::currentTimeMillis);
    }

    DeepSeekModelCatalog(String providerName, String modelsUrl, Path cacheFile, Duration timeout, LongSupplier clock) {
        this.providerName = providerName;
        this.endpoint = URI.create(modelsUrl);
        this.status = "No " + providerName + " model list has been fetched yet.";
        this.cacheFile = cacheFile;
        this.timeout = timeout;
        this.clock = clock;
        this.client = HttpClient.newBuilder().connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER).build();
        if (cacheFile != null && Files.isRegularFile(cacheFile)) {
            try {
                if (Files.size(cacheFile) > MAX_DOCUMENT_BYTES) throw new IOException();
                JsonObject cached = JsonParser.parseString(Files.readString(cacheFile)).getAsJsonObject();
                String source = string(cached, "source");
                if (source != null && !source.equals(endpoint.toString())) throw new IOException();
                models = parseModels(cached.getAsJsonObject("catalog"), providerName);
                hasSnapshot = true;
                status = "Using the last saved " + providerName + " model list until an online refresh succeeds.";
            } catch (IOException | RuntimeException ignored) {
                status = "Saved " + providerName + " model list could not be read; an online refresh is required.";
            }
        }
    }

    synchronized List<String> refresh(String apiKey, boolean force) {
        if (apiKey == null || apiKey.isBlank()) {
            unavailable("Unlock or configure " + providerName + " in Help > Assistants > Manage API Keys, " +
                "then reopen model settings or run Refresh LLM Models.");
            // A newly unlocked vault must not have to wait for the retry interval.
            lastAttempt = Long.MIN_VALUE;
            return ids();
        }
        long now = clock.getAsLong();
        if (!force && lastAttempt != Long.MIN_VALUE && now >= lastAttempt &&
            now - lastAttempt < REFRESH_INTERVAL_MS) return ids();
        lastAttempt = now;
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Bearer " + apiKey).header("Accept", "application/json")
                .GET().build();
            HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                unavailable(providerName + " model refresh failed (HTTP " + response.statusCode() + ").");
                return ids();
            }
            if (response.body().length() > MAX_DOCUMENT_BYTES) throw new IOException();
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            Map<String, Model> updated = parseModels(root, providerName);
            // Replace, never merge: models removed by the server must disappear.
            models = updated;
            hasSnapshot = true;
            status = providerName + " model list refreshed from the official API (" + models.size() + " models).";
            try {
                save(root, now);
            } catch (IOException | RuntimeException ignored) {
                status += " The disk cache could not be saved; this session's list is current.";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            unavailable(providerName + " model refresh was interrupted.");
        } catch (IOException | RuntimeException failure) {
            // Do not log request headers, keys, raw response bodies or exception text.
            unavailable(providerName + " model refresh failed (network timeout, connection error or invalid response).");
        }
        return ids();
    }

    synchronized List<String> ids() { return List.copyOf(models.keySet()); }
    synchronized Model model(String id) { return id == null ? null : models.get(id); }
    synchronized String status() { return status; }

    private void unavailable(String reason) {
        status = reason + (hasSnapshot ? " Using the last successful list; it may be out of date."
            : " No cached model list is available.");
    }

    private void save(JsonObject root, long now) throws IOException {
        if (cacheFile == null) return;
        Path parent = cacheFile.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        JsonObject document = new JsonObject();
        document.addProperty("fetched_at_epoch_ms", now);
        document.addProperty("source", endpoint.toString());
        document.add("catalog", root);
        Path temporary = Files.createTempFile(parent, "llm-models-", ".tmp");
        try {
            Files.writeString(temporary, document.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static Map<String, Model> parseModels(JsonObject root) {
        return parseModels(root, "DeepSeek");
    }

    static Map<String, Model> parseModels(JsonObject root, String providerName) {
        if (root == null || !root.has("data") || !root.get("data").isJsonArray())
            throw new IllegalArgumentException("Expected a model list");
        Map<String, Model> result = new LinkedHashMap<>();
        for (JsonElement entry : root.getAsJsonArray("data")) {
            JsonObject object = entry.getAsJsonObject();
            String id = string(object, "id");
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Missing model ID");
            // Honor explicit metadata when supplied; never classify by a model-name list.
            if ("Volcengine".equals(providerName)) {
                String domain = string(object, "domain");
                if (domain != null && !domain.equalsIgnoreCase("LLM") && !domain.equalsIgnoreCase("VLM")) continue;
                String state = string(object, "status");
                if (state != null && (state.equalsIgnoreCase("Shutdown") || state.equalsIgnoreCase("Retired"))) continue;
            }
            VisionSupport vision = VisionSupport.UNKNOWN;
            JsonObject modalities = object.has("input_modalities") ? object : childObject(object, "modalities");
            if (modalities.has("input_modalities") && modalities.get("input_modalities").isJsonArray()) {
                if (contains(modalities.getAsJsonArray("input_modalities"), "image")) vision = VisionSupport.SUPPORTED;
                // Ark's live catalog can say text-only for models its official
                // model page lists as multimodal. Absence is not reliable negative
                // evidence there; preserve UNKNOWN instead of disabling vision.
                else if (!"Volcengine".equals(providerName)) vision = VisionSupport.UNSUPPORTED;
            }
            String effort = null;
            if (object.has("effort") && object.get("effort").isJsonObject()) {
                JsonObject metadata = object.getAsJsonObject("effort");
                JsonArray levels = metadata.has("supported_levels") && metadata.get("supported_levels").isJsonArray()
                    ? metadata.getAsJsonArray("supported_levels") : new JsonArray();
                String preferred = string(metadata, "default_level");
                if (preferred != null && contains(levels, preferred)) effort = preferred;
                else if (contains(levels, "high")) effort = "high";
                // Without a declared default, omit the parameter and use the server default.
            }
            String name = string(object, "name");
            JsonObject limits = childObject(object, "token_limits");
            Long contextWindow = positiveLong(object, "context_window");
            if (contextWindow == null) contextWindow = positiveLong(limits, "context_window");
            Long maxOutput = positiveLong(object, "max_output_tokens");
            if (maxOutput == null) maxOutput = positiveLong(limits, "max_output_token_length");
            result.putIfAbsent(id, new Model(id, name == null ? id : name, vision,
                contextWindow, maxOutput, effort));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static boolean contains(JsonArray values, String value) {
        for (JsonElement item : values) if (item.isJsonPrimitive() &&
            item.getAsJsonPrimitive().isString() && value.equals(item.getAsString())) return true;
        return false;
    }

    private static JsonObject childObject(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : null;
    }

    private static Long positiveLong(JsonObject object, String key) {
        try {
            JsonElement value = object.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
            long number = value.getAsBigDecimal().longValueExact();
            return number > 0 && number <= Integer.MAX_VALUE ? number : null;
        } catch (RuntimeException ignored) { return null; }
    }
}
