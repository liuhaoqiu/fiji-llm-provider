/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import org.scijava.plugin.Plugin;
import sc.fiji.llm.provider.LLMProvider;

@Plugin(type = LLMProvider.class, name = "SiliconFlow", priority = 96.4)
public class SiliconFlowProvider extends ChatCompletionsProvider {
    @Override public String getName() { return "SiliconFlow"; }
    @Override public String getDescription() { return "SiliconFlow (硅基流动) via the official Chat Completions API"; }
    @Override protected String baseUrl() { return "https://api.siliconflow.cn/v1"; }
    @Override protected String modelsUrl() { return super.modelsUrl() + "?sub_type=chat"; }
    @Override public String getModelsDocumentationUrl() { return "https://api-docs.siliconflow.cn/docs/api/models-get"; }
    @Override public String getApiKeyUrl() { return "https://cloud.siliconflow.cn/account/ak"; }
}
