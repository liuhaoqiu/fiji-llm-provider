/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.scijava.plugin.Plugin;
import sc.fiji.llm.provider.LLMProvider;

@Plugin(type = LLMProvider.class, name = "Volcengine", priority = 96.3)
public class VolcengineProvider extends ChatCompletionsProvider {
    static final String CUSTOM_MODEL = "<Enter model / Endpoint ID...>";
    private String customModel;

    @Override public String getName() { return "Volcengine"; }
    @Override public String getDescription() { return "Volcengine (火山方舟): model IDs or inference Endpoint IDs"; }
    @Override protected String baseUrl() { return "https://ark.cn-beijing.volces.com/api/v3"; }
    @Override public String getModelsDocumentationUrl() { return "https://docs.volcengine.com/docs/ark/model-list"; }
    @Override public String getApiKeyUrl() { return "https://ark.volcengine.com/region:cn-beijing/apiKey"; }

    private String customModel() {
        if (customModel == null) {
            var prefs = preferences();
            customModel = prefs == null ? "" : prefs.get(VolcengineProvider.class, "customModel", "");
        }
        return customModel;
    }

    @Override public List<String> getAvailableModels() {
        var choices = new ArrayList<>(super.getAvailableModels());
        String custom = customModel();
        if (validIdentifier(custom) && !choices.contains(custom)) choices.add(0, custom);
        choices.add(CUSTOM_MODEL);
        return List.copyOf(choices);
    }

    @Override public Optional<ValidatedModel> validateModel(String modelName) {
        if (CUSTOM_MODEL.equals(modelName)) {
            disableAutoStart();
            String entered = promptCustomModel(customModel());
            if (entered == null) return Optional.empty();
            entered = entered.trim();
            if (!validIdentifier(entered)) return Optional.empty();
            customModel = entered;
            var prefs = preferences();
            if (prefs != null) prefs.put(VolcengineProvider.class, "customModel", entered);
            return Optional.of(new ValidatedModel(entered, entered));
        }
        if (isConfiguredModel(modelName)) return Optional.of(new ValidatedModel(modelName, modelName));
        return super.validateModel(modelName);
    }

    @Override protected void requireAvailableModel(String modelName) {
        if (!isConfiguredModel(modelName)) super.requireAvailableModel(modelName);
    }

    private boolean isConfiguredModel(String value) {
        // A previous conversation can refer to an earlier ep-* endpoint.
        return validIdentifier(value) && (value.equals(customModel()) || value.startsWith("ep-"));
    }

    private static boolean validIdentifier(String value) {
        return value != null && value.length() <= 256 && value.matches("[A-Za-z0-9][A-Za-z0-9._/-]*");
    }

    protected String promptCustomModel(String previous) {
        if (java.awt.GraphicsEnvironment.isHeadless()) return null;
        var entered = new AtomicReference<String>();
        Runnable prompt = () -> entered.set((String) javax.swing.JOptionPane.showInputDialog(null,
            "Enter an enabled Model ID or Endpoint ID (ep-...) from your Ark console:",
            "Volcengine model", javax.swing.JOptionPane.QUESTION_MESSAGE, null, null, previous));
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) prompt.run();
            else javax.swing.SwingUtilities.invokeAndWait(prompt);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (java.lang.reflect.InvocationTargetException failed) { return null; }
        return entered.get();
    }
}
