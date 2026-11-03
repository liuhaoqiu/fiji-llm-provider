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
    private SiliconFlowModelMetadata publicMetadata;
    @Override public String getName() { return "SiliconFlow"; }
    @Override public String getDescription() { return "SiliconFlow (硅基流动) via the official Chat Completions API"; }
    @Override protected String baseUrl() { return "https://api.siliconflow.cn/v1"; }
    @Override protected String modelsUrl() { return super.modelsUrl() + "?sub_type=chat"; }
    @Override public String getModelsDocumentationUrl() { return "https://api-docs.siliconflow.cn/docs/api/models-get"; }
    @Override public String getApiKeyUrl() { return "https://cloud.siliconflow.cn/account/ak"; }
    private boolean publicMetadataEnabled() {
        return getContext()!=null && modelCacheFile()!=null && modelsUrl().startsWith("https://api.siliconflow.cn/");
    }
    private synchronized SiliconFlowModelMetadata publicMetadata() {
        if(publicMetadata==null) publicMetadata=new SiliconFlowModelMetadata(modelCacheFile().resolveSibling("siliconflow-official-metadata-v1.json"));
        return publicMetadata;
    }
    @Override protected Long reportedContextWindow(String modelName) {
        Long fromApi=super.reportedContextWindow(modelName);
        return fromApi!=null || !publicMetadataEnabled() ? fromApi : publicMetadata().window(modelName);
    }
    @Override String contextWindowSource(String modelName) {
        if(AssistantExecutionSettings.inputTokens(preferences())>0) return super.contextWindowSource(modelName);
        if(super.reportedContextWindow(modelName)!=null) return "provider API metadata";
        if(publicMetadataEnabled() && publicMetadata().window(modelName)!=null) return "SiliconFlow official model page metadata (cached up to 24 h)";
        return "metadata unavailable: conservative 32768-token fallback";
    }
    @Override public java.util.List<String> refreshAvailableModels() {
        var ids=super.refreshAvailableModels();
        if(publicMetadataEnabled()) publicMetadata().refresh(true);
        return ids;
    }
}
