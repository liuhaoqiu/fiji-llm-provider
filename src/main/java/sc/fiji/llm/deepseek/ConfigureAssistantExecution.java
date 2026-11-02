/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import org.scijava.ItemVisibility;
import org.scijava.command.Command;
import org.scijava.command.ContextCommand;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.prefs.PrefService;
import org.scijava.ui.UIService;
import sc.fiji.llm.assistant.AssistantService;

@Plugin(type = Command.class, menuPath = "Help>Assistants>LLM Execution Settings...",
    initializer = "initialize")
public final class ConfigureAssistantExecution extends ContextCommand {
    @Parameter private AssistantService assistantService;
    @Parameter private PrefService prefs;
    @Parameter private UIService ui;

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String status;

    @Parameter(label = "Maximum tool rounds per message", min = "1", persist = false)
    private int maxToolCallingRoundTrips = AssistantExecutionSettings.DEFAULT_ROUND_TRIPS;

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String scope = "Applies to DeepSeek, SiliconFlow, Volcengine and NVIDIA.\n" +
        "One round can contain several tool calls. Reopen Fiji Chat after saving.";

    public void initialize() {
        maxToolCallingRoundTrips = AssistantExecutionSettings.roundTrips(prefs);
        status = assistantService instanceof ProviderAssistantService
            ? "Fiji LLM Provider execution control: ACTIVE"
            : "Execution control is NOT ACTIVE. Selected service: " + assistantService.getClass().getName();
    }

    @Override public void run() {
        if (isCanceled()) return;
        if (!(assistantService instanceof ProviderAssistantService)) {
            ui.showDialog("Fiji LLM Provider execution control is not active.\n" +
                "Settings were not changed. Check extension compatibility after a Fiji Chat update.\n" +
                "Selected service: " + assistantService.getClass().getName(), "LLM Execution Settings");
            return;
        }
        if (maxToolCallingRoundTrips < 1) {
            ui.showDialog("Enter a positive number of tool rounds.", "LLM Execution Settings");
            return;
        }
        AssistantExecutionSettings.save(prefs, maxToolCallingRoundTrips);
        ui.showDialog("Saved: " + maxToolCallingRoundTrips + " tool rounds per message.\n" +
            "Applies to DeepSeek, SiliconFlow, Volcengine and NVIDIA.\n" +
            "Reopen Fiji Chat to apply this to an existing chat window.\n" +
            "Running requests are unchanged.", "LLM Execution Settings");
    }
}
