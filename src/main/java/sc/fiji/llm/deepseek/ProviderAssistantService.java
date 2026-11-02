/*
 * SPDX-License-Identifier: BSD-2-Clause AND PolyForm-Noncommercial-1.0.0
 * Original extension additions Copyright (c) 2026 Haoqiu Liu.
 * Upstream portions retain the BSD notice below. See NOTICE.txt for scope.
 */
/*-
 * Fiji software for LLM integration.
 * Copyright (C) 2025 - 2026 Fiji developers.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package sc.fiji.llm.deepseek;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.service.AiServices;
import org.scijava.Priority;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.service.Service;
import sc.fiji.llm.assistant.DefaultAssistantService;
import sc.fiji.llm.chat.ImageToolResultNormalizer;
import sc.fiji.llm.image.ImageRenderingService;
import sc.fiji.llm.provider.ProviderService;
import sc.fiji.llm.tools.AiToolService;

/**
 * Uses the public SciJava service extension point, not patched core classes or
 * reflective access to LangChain4j internals. The builder wiring below follows
 * Fiji's DefaultAssistantService (0.2.0-SNAPSHOT); retain its request transformer,
 * image policy, memory and tool-error handlers when adapting to future versions.
 * https://github.com/fiji/fiji-llm/blob/main/src/main/java/sc/fiji/llm/assistant/DefaultAssistantService.java
 */
@Plugin(type = Service.class, priority = Priority.HIGH)
public class ProviderAssistantService extends DefaultAssistantService {
    @Parameter private ProviderService providers;
    @Parameter private AiToolService tools;
    @Parameter private ImageRenderingService images;
    @Parameter private PrefService prefs;

    public int configuredRoundTrips() { return AssistantExecutionSettings.roundTrips(prefs); }

    @Override
    public <T> T createAssistant(Class<T> assistantInterface, String providerName,
        String modelName, ChatMemory chatMemory, ChatRequestParameters defaultChatParameters)
    {
        var provider = providers.getProvider(providerName);
        // Only the four providers supplied by this extension get the override.
        // Other providers keep the installed Fiji implementation, including its
        // future changes. Unknown provider names retain the original error path.
        if (!(provider instanceof AbstractCatalogProvider)) {
            return super.createAssistant(assistantInterface, providerName, modelName,
                chatMemory, defaultChatParameters);
        }
        images.setLocalModel(!provider.requiresApiKey());
        var builder = AiServices.builder(assistantInterface)
            .streamingChatModel(provider.createStreamingChatModel(modelName))
            .tools(tools.getToolsWithExecutors())
            .toolExecutionErrorHandler(tools::handleExecutionError)
            .toolArgumentsErrorHandler(tools::handleArgumentError)
            .chatModel(provider.createChatModel(modelName))
            .maxToolCallingRoundTrips(configuredRoundTrips());

        builder.chatRequestTransformer(request -> {
            ChatRequest transformed = request;
            if (defaultChatParameters != null) {
                transformed = request.toBuilder().parameters(
                    defaultChatParameters.overrideWith(request.parameters())).build();
            }
            if (!provider.supportsImageToolResults(modelName)) {
                transformed = ImageToolResultNormalizer.normalize(transformed);
            }
            return transformed;
        });
        if (chatMemory != null) builder.chatMemory(chatMemory);
        return builder.build();
    }
}
