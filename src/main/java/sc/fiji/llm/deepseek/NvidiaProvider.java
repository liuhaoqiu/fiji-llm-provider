/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import org.scijava.plugin.Plugin;
import sc.fiji.llm.provider.LLMProvider;

/** NVIDIA hosted API Catalog, using the shared Chat Completions transport. */
@Plugin(type = LLMProvider.class, name = "NVIDIA", priority = 96.2)
public class NvidiaProvider extends ChatCompletionsProvider {
    @Override public String getName() { return "NVIDIA"; }
    @Override public String getDescription() { return "NVIDIA hosted models via the official Chat Completions API"; }
    @Override protected String baseUrl() { return "https://integrate.api.nvidia.com/v1"; }
    @Override public String getModelsDocumentationUrl() { return "https://docs.api.nvidia.com/nim/reference/llm-apis"; }
    @Override public String getApiKeyUrl() { return "https://build.nvidia.com/"; }

    @Override public String getModelCatalogStatus() {
        // The public /models list also includes embeddings and supplies no
        // task/capability metadata. Do not guess capability from model names.
        return super.getModelCatalogStatus() +
            " The NVIDIA catalog includes non-chat models. Choose a model supporting Chat Completions and function calling; image support depends on the model.";
    }
}
