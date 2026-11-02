/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;
import sc.fiji.llm.provider.ProviderService;

@Plugin(type = Command.class, menuPath = "Help>Assistants>Refresh LLM Models...")
public final class RefreshLLMModels implements Command {
    @Parameter private ProviderService providers;
    @Parameter private UIService ui;
    @Parameter(label = "Service", choices = {"DeepSeek", "SiliconFlow", "Volcengine", "NVIDIA"})
    private String provider = "DeepSeek";

    @Override public void run() {
        var selected = providers.getProvider(provider);
        if (!(selected instanceof AbstractCatalogProvider catalogProvider)) {
            ui.showDialog("The selected provider is not installed.", "Fiji LLM Provider");
            return;
        }
        if (!catalogProvider.ensureApiKeyAvailable()) return;
        var models = catalogProvider.refreshAvailableModels();
        String preview = String.join("\n", models.stream().limit(20).toList());
        if (models.size() > 20) preview += "\n... (" + models.size() + " models in total)";
        ui.showDialog(catalogProvider.getModelCatalogStatus().replace(". ", ".\n") + "\n\n" + preview +
            "\n\nReopen model settings to see the updated list.", "Fiji LLM Provider - " + provider);
    }
}
