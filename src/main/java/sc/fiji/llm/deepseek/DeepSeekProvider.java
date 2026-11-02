/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import org.scijava.plugin.Plugin;
import sc.fiji.llm.provider.LLMProvider;

/** Keep the provider name and class stable for existing Fiji vaults and chats. */
@Plugin(type = LLMProvider.class, name = "DeepSeek", priority = 96.5)
public class DeepSeekProvider extends AbstractCatalogProvider {
    static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    static final String LOAD_MODELS = "<Unlock / refresh DeepSeek models...>";

    @Override public String getName() { return "DeepSeek"; }
    @Override public String getDescription() { return "DeepSeek models via the official Responses API"; }
    @Override protected String baseUrl() { return DEFAULT_BASE_URL; }
    @Override public String getModelsDocumentationUrl() { return "https://api-docs.deepseek.com/quick_start/pricing/"; }
    @Override public String getApiKeyUrl() { return "https://platform.deepseek.com/api_keys"; }
}
