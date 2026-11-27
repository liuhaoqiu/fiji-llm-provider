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

    @Parameter(label = "Context window tokens (0 = model metadata)", min = "0", persist = false)
    private int maxInputTokens = AssistantExecutionSettings.DEFAULT_INPUT_TOKENS;

    @Parameter(label = "Maximum request body MiB (0 = provider default)", min = "0", max = "128", persist = false)
    private int maxRequestMiB = AssistantExecutionSettings.DEFAULT_REQUEST_MIB;

    @Parameter(label = "Reserved output tokens", min = "1", persist = false)
    private int outputReserveTokens = AssistantExecutionSettings.DEFAULT_OUTPUT_RESERVE;

    @Parameter(label = "Save each chat in its own Fiji_chat_output folder next to the input image", persist = false)
    private boolean chatOutputFolder = true;

    @Parameter(label = "Shorten very long lists in tool results sent to the model", persist = false)
    private boolean shortenLongLists = true;

    // SciJava shows a message parameter as one label: "\n" is not a line break there,
    // so plain text makes the dialog as wide as the whole note. HTML with a fixed
    // width wraps, as Fiji Chat does for its own welcome message.
    static final String SCOPE = "<html><body style='width: 420px'>" +
        "Applies to DeepSeek, SiliconFlow, Volcengine and NVIDIA.<br>" +
        "One round can contain several tool calls. Reopen Fiji Chat after saving.<br>" +
        "512K = 524288; 1M = 1048576 total tokens, including reserved output.<br>" +
        "Old context is excerpted automatically; complete records stay on this computer.<br>" +
        "1 MiB = 1,048,576 bytes, including encoded images and reasoning data.<br>" +
        "The output folder is named Fiji_chat_output_&lt;date&gt;_&lt;number&gt; and holds results, scripts, the report and a step log.<br>" +
        "Shortening applies only to a tool result above 30,000 characters: its long lists keep 20 rows and say how many were omitted. Text is never cut; the full result stays on this computer." +
        "</body></html>";

    @Parameter(visibility = ItemVisibility.MESSAGE, persist = false)
    private String scope = SCOPE;

    public void initialize() {
        maxToolCallingRoundTrips = AssistantExecutionSettings.roundTrips(prefs);
        maxInputTokens = AssistantExecutionSettings.inputTokens(prefs);
        maxRequestMiB = AssistantExecutionSettings.requestMiB(prefs);
        outputReserveTokens = AssistantExecutionSettings.outputReserve(prefs);
        chatOutputFolder = AssistantExecutionSettings.outputFolder(prefs);
        shortenLongLists = AssistantExecutionSettings.shortenLists(prefs);
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
        if (maxToolCallingRoundTrips < 1 || maxInputTokens < 0 || maxRequestMiB < 0 || maxRequestMiB > 128 || outputReserveTokens < 1) {
            ui.showDialog("Use positive rounds/output tokens, nonnegative context tokens and 0-128 MiB.", "LLM Execution Settings");
            return;
        }
        AssistantExecutionSettings.save(prefs, maxToolCallingRoundTrips, maxInputTokens, maxRequestMiB);
        prefs.put(AssistantExecutionSettings.class, AssistantExecutionSettings.OUTPUT_RESERVE_KEY, outputReserveTokens);
        AssistantExecutionSettings.saveOutputFolder(prefs, chatOutputFolder);
        AssistantExecutionSettings.saveShortenLists(prefs, shortenLongLists);
        ui.showDialog("Saved: " + maxToolCallingRoundTrips + " tool rounds per message.\n" +
            "Context window: " + (maxInputTokens == 0 ? "automatic" : maxInputTokens) +
            "; request MiB: " + (maxRequestMiB == 0 ? "provider default" : maxRequestMiB) + ".\n" +
            "Requested output reserve: " + outputReserveTokens + " tokens (clamped to model/window limits).\n" +
            "Fiji_chat_output folder per chat: " + (chatOutputFolder ? "on" : "off") + ".\n" +
            "Shorten very long lists in tool results: " + (shortenLongLists ? "on" : "off") + ".\n" +
            "Applies to DeepSeek, SiliconFlow, Volcengine and NVIDIA.\n" +
            "Reopen Fiji Chat to apply this to an existing chat window.\n" +
            "Running requests are unchanged.", "LLM Execution Settings");
    }
}
