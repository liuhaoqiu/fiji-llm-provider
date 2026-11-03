/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

/** OpenAI Chat Completions transport used by SiliconFlow and Volcengine. */
public abstract class ChatCompletionsProvider extends AbstractCatalogProvider {
    @Override public boolean supportsImageToolResults(String modelName) {
        // Fiji's existing normalizer moves image tool outputs into a user message,
        // keeping tool replies as strings as required by these Chat APIs.
        return false;
    }

    @Override public ChatRequestParameters defaultChatRequestParameters(String modelName) {
        // Do not impose sampling or reasoning parameters on heterogeneous catalogs.
        return OpenAiChatRequestParameters.builder().build();
    }

    @Override public ChatModel createChatModel(String modelName) {
        requireAvailableModel(modelName);
        return OpenAiChatModel.builder().baseUrl(baseUrl()).apiKey(apiKey()).modelName(modelName)
            .timeout(responseTimeout()).httpClientBuilder(httpClientBuilder())
            .defaultRequestParameters(defaultChatRequestParameters(modelName))
            .returnThinking(true).sendThinking(true).listeners(monitoredListeners(modelName)).build();
    }

    @Override public StreamingChatModel createStreamingChatModel(String modelName) {
        requireAvailableModel(modelName);
        return OpenAiStreamingChatModel.builder().baseUrl(baseUrl()).apiKey(apiKey()).modelName(modelName)
            .timeout(responseTimeout()).httpClientBuilder(httpClientBuilder())
            .defaultRequestParameters(defaultChatRequestParameters(modelName))
            .returnThinking(true).sendThinking(true).listeners(monitoredListeners(modelName)).build();
    }
}
