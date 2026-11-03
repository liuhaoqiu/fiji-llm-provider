/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.nio.charset.StandardCharsets;
import dev.langchain4j.model.chat.request.ChatRequest;

/** Non-destructive preflight limits. Never log request bodies or credentials. */
final class RequestBudget {
    private RequestBudget() {}

    static void checkTokens(ChatRequest request, long maximum) {
        checkTokens(request, maximum, 16384);
    }

    static void checkTokens(ChatRequest request, long maximum, int imageAllowance) {
        long count = estimatedTokens(request, imageAllowance);
        if (count > maximum) throw exceeded("estimated input tokens", count, maximum);
    }

    static long estimatedTokens(ChatRequest request) {
        return estimatedTokens(request, 16384);
    }

    static long estimatedTokens(ChatRequest request, int imageAllowance) {
        var estimator = new MultimodalTokenCountEstimator(imageAllowance);
        long count = 0;
        for (var message : request.messages()) {
            count += estimator.estimateTokenCountInMessage(message);
            if (message instanceof dev.langchain4j.data.message.AiMessage ai) {
                if (ai.thinking() != null) count += estimator.estimateTokenCountInText(ai.thinking());
                String opaque = ai.attribute("encrypted_reasoning", String.class);
                if (opaque != null) count += (opaque.length() + 3L) / 4L;
            }
        }
        // Tool definitions consume input context too. This remains an estimate,
        // not a provider-specific tokenizer or a claim about its context window.
        if (request.toolSpecifications() != null)
            for (var tool : request.toolSpecifications())
                count += estimator.estimateTokenCountInText(tool.toString());
        return count;
    }

    static void checkBytes(String body, long maximum) {
        long bytes = body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
        ContextMonitor.body(bytes, maximum);
        if (bytes > maximum) throw exceeded("request bytes (UTF-8 JSON)", bytes, maximum);
    }

    private static IllegalArgumentException exceeded(String unit, long actual, long maximum) {
        return new IllegalArgumentException("Fiji LLM Provider context limit reached: " +
            actual + " " + unit + ", configured maximum " + maximum + ". " +
            "The request was stopped before transmission. Save your current scripts/results " +
            "and continue in a new conversation with a short progress summary, or adjust " +
            "Help > Assistants > LLM Execution Settings... if the provider supports a larger request. " +
            "Increasing this setting cannot override the provider's own limits.");
    }
}
