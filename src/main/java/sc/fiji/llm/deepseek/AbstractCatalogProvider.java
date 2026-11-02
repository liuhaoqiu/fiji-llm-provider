/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.time.LocalDate;
import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.http.client.HttpClientBuilder;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesChatModel;
import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiResponsesStreamingChatModel;
import sc.fiji.llm.provider.AbstractLLMProvider;
import sc.fiji.llm.provider.LLMProvider;

/**
 * Shared key-manager, model-discovery and multimodal memory integration.
 */
public abstract class AbstractCatalogProvider extends AbstractLLMProvider {

	private DeepSeekModelCatalog modelCatalog;
	private String lastReportedStatus;

	/** Shared policy for all three services and both transport protocols. */
	protected Duration responseTimeout() { return Duration.ofMinutes(30); }

	protected HttpClientBuilder httpClientBuilder() {
		// Responses has no model-level timeout setter: configure its HTTP builder
		// directly. Chat Completions resets both values from its model timeout,
		// so keep connection setup bounded independently in either protocol.
		return new JdkHttpClientBuilder() {
			@Override public JdkHttpClientBuilder connectTimeout(Duration ignored) {
				return super.connectTimeout(Duration.ofSeconds(15));
			}
		}.connectTimeout(Duration.ofSeconds(15)).readTimeout(responseTimeout());
	}

	protected String loadModelsLabel() { return "<Unlock / refresh " + getName() + " models...>"; }

	@Override
	public List<String> getAvailableModels() {
		List<String> ids = refreshModels(false);
		if (!ids.isEmpty()) return ids;
		// Fiji_Chat auto-runs again when it sees an empty list, before clearing
		// AUTO_RUN. An actionable setup choice avoids that recursive error path.
		disableAutoStart();
		return List.of(loadModelsLabel());
	}

	protected void disableAutoStart() {
		var prefs = preferences();
		if (prefs != null) prefs.remove(sc.fiji.llm.commands.Fiji_Chat.class,
			sc.fiji.llm.commands.Fiji_Chat.AUTO_RUN);
	}

	protected org.scijava.prefs.PrefService preferences() {
		return getContext() == null ? null : getContext().getService(org.scijava.prefs.PrefService.class);
	}

	/** Use Fiji's own key manager; never maintain another password/key store. */
	protected boolean ensureApiKeyAvailable() {
		try {
			String key = apiKey();
			if (key != null && !key.isBlank()) return true;
		} catch (IllegalStateException unavailable) { /* Prompt through the normal Fiji workflow. */ }
		if (getContext() == null) return false;
		var modules = getContext().getService(org.scijava.module.ModuleService.class);
		var command = keyManagerCommand();
		if (modules == null || command == null) return false;
		try {
			// Manage_Keys is a DynamicCommand (already a Module). CommandService
			// starts it but deliberately returns null instead of Future<CommandModule>.
			// ModuleService retains the real future, including preprocessing/dialogs.
			var pending = modules.run(command, true,
				java.util.Map.of("provider", getName(), "startChatbot", false));
			if (pending == null) return false;
			var module = pending.get();
			if (module == null || (module instanceof org.scijava.Cancelable canceled &&
				canceled.isCanceled())) return false;
			String key = apiKey();
			return key != null && !key.isBlank();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return false;
		} catch (java.util.concurrent.ExecutionException | IllegalStateException failed) {
			return false;
		}
	}

	protected org.scijava.command.CommandInfo keyManagerCommand() {
		if (getContext() == null) return null;
		var commands = getContext().getService(org.scijava.command.CommandService.class);
		return commands == null ? null : commands.getCommand(sc.fiji.llm.commands.Manage_Keys.class);
	}

	private Optional<ValidatedModel> loadAndSelectModel() {
		disableAutoStart();
		if (!ensureApiKeyAvailable()) return Optional.empty();
		List<String> ids = refreshAvailableModels();
		if (ids.isEmpty()) {
			showCatalogStatus();
			return Optional.empty();
		}
		String chosen = chooseModel(ids);
		return chosen != null && ids.contains(chosen)
			? Optional.of(new ValidatedModel(chosen, chosen)) : Optional.empty();
	}

	protected String chooseModel(List<String> ids) {
		if (java.awt.GraphicsEnvironment.isHeadless()) return null;
		var choice = new java.util.concurrent.atomic.AtomicReference<String>();
		Runnable prompt = () -> {
			Object selected = javax.swing.JOptionPane.showInputDialog(null,
				"Select an available " + getName() + " model:", getName() + " models",
				javax.swing.JOptionPane.QUESTION_MESSAGE, null, ids.toArray(), ids.get(0));
			if (selected instanceof String value) choice.set(value);
		};
		try {
			if (javax.swing.SwingUtilities.isEventDispatchThread()) prompt.run();
			else javax.swing.SwingUtilities.invokeAndWait(prompt);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
		} catch (java.lang.reflect.InvocationTargetException failed) {
			return null;
		}
		return choice.get();
	}

	protected void showCatalogStatus() {
		if (getContext() == null) return;
		var ui = getContext().getService(org.scijava.ui.UIService.class);
		if (ui != null) ui.showDialog(getModelCatalogStatus().replace(". ", ".\n"), getName() + " models");
	}

	/** Explicit refresh bypasses the one-minute request coalescing window. */
	public List<String> refreshAvailableModels() { return refreshModels(true); }

	public String getModelCatalogStatus() { return catalog().status(); }

	private synchronized DeepSeekModelCatalog catalog() {
		if (modelCatalog == null) modelCatalog = new DeepSeekModelCatalog(getName(), modelsUrl(), modelCacheFile());
		return modelCatalog;
	}

	private List<String> refreshModels(boolean force) {
		String key = null;
		try { key = apiKey(); }
		catch (IllegalStateException unavailable) { /* Vault is locked or key is missing. */ }
		List<String> ids = catalog().refresh(key, force);
		String status = catalog().status();
		if (!status.equals(lastReportedStatus) && getContext() != null) {
			org.scijava.log.LogService log = getContext().getService(org.scijava.log.LogService.class);
			if (log != null) log.info(status);
			lastReportedStatus = status;
		}
		return ids;
	}

	@Override
	protected String apiKey() {
		// Tests and plugin discovery can instantiate a provider without a context.
		if (getContext() == null) throw new IllegalStateException("No Fiji context");
		return super.apiKey();
	}

	protected Path modelCacheFile() {
		String localAppData = System.getenv("LOCALAPPDATA");
		Path root = localAppData == null || localAppData.isBlank()
			? Path.of(System.getProperty("user.home"), ".cache") : Path.of(localAppData);
		return root.resolve("FijiChat").resolve(getName().toLowerCase(java.util.Locale.ROOT) + "-models-v1.json");
	}

	protected DeepSeekModelCatalog.Model modelInfo(String modelName) {
		if (catalog().model(modelName) == null) refreshModels(false);
		return catalog().model(modelName);
	}

	protected void requireAvailableModel(String modelName) {
		// Setup UI actions are not model IDs and must never reach a model builder.
		if (modelName == null || loadModelsLabel().equals(modelName) ||
			!refreshModels(false).contains(modelName)) throw new IllegalArgumentException(
			"The selected model is not in the current " + getName() + " model list. " +
			"Reopen model settings and choose an available model. " + getModelCatalogStatus());
	}

	@Override
	public Optional<ValidatedModel> validateModel(final String modelName) {
		if (loadModelsLabel().equals(modelName)) return loadAndSelectModel();
		return modelName != null && refreshModels(false).contains(modelName) ? Optional.of(new ValidatedModel(modelName,
			modelName)) : Optional.empty();
	}

	@Override
	public VisionSupport getVisionSupport(final String modelName) {
		DeepSeekModelCatalog.Model model = modelInfo(modelName);
		return model == null ? VisionSupport.UNKNOWN : model.vision();
	}

	@Override
	public boolean supportsImageToolResults(final String modelName) {
		return getVisionSupport(modelName) == VisionSupport.SUPPORTED;
	}

	@Override
	public ChatRequestParameters defaultChatRequestParameters(
		final String modelName)
	{
		DeepSeekModelCatalog.Model model = modelInfo(modelName);
		var parameters = OpenAiResponsesChatRequestParameters.builder();
		if (model != null && model.reasoningEffort() != null)
			parameters.reasoningEffort(model.reasoningEffort());
		return parameters.build();
	}

	@Override
	public LocalDate getModelsDocumentationLastModified() {
		return LocalDate.of(2026, 11, 2);
	}

	@Override
	public TokenWindowChatMemory createTokenChatMemory(final String modelName) {
		/*
		 * jtokkit does not know DeepSeek model IDs. gpt-4o uses the installed
		 * O200K encoding and is used only as a conservative local approximation.
		 */
		DeepSeekModelCatalog.Model model = modelInfo(modelName);
		return TokenWindowChatMemory.withMaxTokens(model == null ? 32_768 : model.memoryTokenLimit(),
			new MultimodalTokenCountEstimator());
	}

	@Override
	public ChatModel createChatModel(final String modelName) {
		requireAvailableModel(modelName);
		return OpenAiResponsesChatModel.builder().baseUrl(baseUrl()).apiKey(apiKey())
			.httpClientBuilder(httpClientBuilder())
			.modelName(modelName).defaultRequestParameters(
				defaultChatRequestParameters(modelName)).listeners(listeners()).build();
	}

	@Override
	public StreamingChatModel createStreamingChatModel(final String modelName) {
		requireAvailableModel(modelName);
		return OpenAiResponsesStreamingChatModel.builder().baseUrl(baseUrl()).apiKey(
			apiKey()).httpClientBuilder(httpClientBuilder()).modelName(modelName).defaultRequestParameters(
				defaultChatRequestParameters(modelName)).listeners(listeners()).build();
	}

	/** Overridable only to support an isolated local transport test. */
	protected abstract String baseUrl();

	protected String modelsUrl() { return baseUrl().replaceAll("/+$", "") + "/models"; }
}
