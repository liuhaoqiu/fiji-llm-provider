/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.util.List;

import org.scijava.Context;
import org.scijava.plugin.Plugin;
import org.scijava.plugin.PluginInfo;
import org.scijava.plugin.PluginService;

import dev.langchain4j.model.openai.OpenAiResponsesChatRequestParameters;
import sc.fiji.llm.provider.LLMProvider;

/** Self-contained contract and SciJava discovery checks; no API key required. */
public final class VerificationMain {

	private VerificationMain() {}

	public static void main(final String[] args) {
		final DeepSeekProvider provider = new DeepSeekProvider() {
			@Override protected java.nio.file.Path modelCacheFile() { return null; }
		};
		check("DeepSeek".equals(provider.getName()), "provider name");
		check(provider.getAvailableModels().equals(List.of(DeepSeekProvider.LOAD_MODELS)),
			"actionable setup choice instead of the core's empty-list loop");
		check(provider.validateModel(DeepSeekProvider.LOAD_MODELS).isEmpty(),
			"unavailable setup cancels without inventing a model");
		check(provider.validateModel("gpt-4o").isEmpty(),
			"unknown model rejection");
		check(provider.getVisionSupport("deepseek-flash") ==
			LLMProvider.VisionSupport.UNKNOWN, "vision not guessed without metadata");
		check(provider.getVisionSupport("deepseek-v4-pro") ==
			LLMProvider.VisionSupport.UNKNOWN, "vision not guessed from name");
		check(provider.getVisionSupport("unknown") ==
			LLMProvider.VisionSupport.UNKNOWN, "unknown vision");
		check(!provider.supportsImageToolResults("deepseek-flash"),
			"image tool results require metadata");
		check(!provider.supportsImageToolResults("deepseek-v4-pro"),
			"pro image tool results");
		check(provider.createTokenChatMemory("deepseek-flash") != null,
			"flash memory");
		check(provider.createTokenChatMemory("deepseek-v4-pro") != null,
			"pro memory");

		final OpenAiResponsesChatRequestParameters parameters =
			(OpenAiResponsesChatRequestParameters) provider
				.defaultChatRequestParameters("deepseek-flash");
		check(parameters.reasoningEffort() == null, "no hardcoded effort without metadata");

		final Plugin annotation = DeepSeekProvider.class.getAnnotation(Plugin.class);
		check(annotation != null, "@Plugin annotation");
		check("DeepSeek".equals(annotation.name()), "@Plugin name");
		check(annotation.type() == LLMProvider.class, "@Plugin type");

		try (Context context = new Context(PluginService.class)) {
			final PluginService pluginService = context.service(PluginService.class);
			final List<PluginInfo<LLMProvider>> providers = pluginService
				.getPluginsOfType(LLMProvider.class);
			final long deepSeekCount = providers.stream().filter(info -> "DeepSeek"
				.equals(info.getName())).count();
			check(deepSeekCount == 1, "exactly one discovered DeepSeek provider");
			check(pluginService.getPluginsOfType(org.scijava.command.Command.class).stream()
				.anyMatch(info -> info.getClassName().equals(RefreshLLMModels.class.getName())),
				"refresh command discovery");
			for (String added : List.of("SiliconFlow", "Volcengine", "NVIDIA"))
				check(providers.stream().filter(info -> added.equals(info.getName())).count() == 1,
					"exactly one discovered " + added + " provider");
			check(pluginService.getPluginsOfType(org.scijava.command.Command.class).stream()
				.noneMatch(info -> info.getClassName().endsWith("RefreshDeepSeekModels")), "old refresh menu removed");
			check(hasProvider(providers, "ChatGPT"), "existing ChatGPT provider");
			check(hasProvider(providers, "Claude"), "existing Claude provider");
			check(hasProvider(providers, "Gemini"), "existing Gemini provider");
			check(hasProvider(providers, "Ollama (Custom)"),
				"existing Ollama provider");
		}

		try {
			var selector = RefreshLLMModels.class.getDeclaredField("provider")
				.getAnnotation(org.scijava.plugin.Parameter.class);
			check(java.util.Arrays.asList(selector.choices()).contains("NVIDIA"), "NVIDIA refresh-menu choice");
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		System.out.println("PASS: provider contract and SciJava discovery (four extension services)");
	}

	private static boolean hasProvider(
		final List<PluginInfo<LLMProvider>> providers, final String name)
	{
		return providers.stream().anyMatch(info -> name.equals(info.getName()));
	}

	private static void check(final boolean condition, final String label) {
		if (!condition) throw new AssertionError("FAILED: " + label);
	}
}
