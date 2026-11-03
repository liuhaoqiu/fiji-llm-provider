/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolErrorHandlerResult;
import dev.langchain4j.service.tool.ToolExecutor;
import org.scijava.Context;
import org.scijava.command.CommandService;
import org.scijava.plugin.PluginService;
import org.scijava.prefs.PrefService;
import org.scijava.ui.UIService;
import sc.fiji.llm.assistant.AssistantService;
import sc.fiji.llm.assistant.DefaultAssistantService;
import sc.fiji.llm.image.ImageRenderingService;
import sc.fiji.llm.provider.AbstractLLMProvider;
import sc.fiji.llm.provider.LLMProvider;
import sc.fiji.llm.provider.ProviderService;
import sc.fiji.llm.tools.AiToolService;

/** Actual SciJava selection and LangChain4j loops; no API keys, network or image mutation. */
public final class AssistantExecutionVerificationMain {
    static { net.imagej.patcher.LegacyInjector.preinit(); }
    public interface Assistant {
        TokenStream stream(String message);
        String chat(String message);
    }

    private static final ToolSpecification TOOL = ToolSpecification.builder()
        .name("synthetic_probe").description("Read a synthetic test result")
        .parameters(JsonObjectSchema.builder().build()).build();
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5xkAAAAASUVORK5CYII=";

    /** Asynchronous dispatch avoids artificial Java recursion across 1000 rounds. */
    static final class Scenario implements AutoCloseable {
        final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicInteger executions = new AtomicInteger();
        final AtomicReference<ChatRequest> observed = new AtomicReference<>();
        final AtomicReference<Boolean> localModel = new AtomicReference<>();
        final AtomicInteger handledErrors = new AtomicInteger();
        int rounds, batch = 1;
        int failRequest = -1;
        String result = "result";
        boolean nativeImages, failTool;
        final ChatModel sync = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) { return next(request); }
        };
        final StreamingChatModel streaming = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                executor.execute(() -> {
                    try {
                        if (requests.get() + 1 == failRequest)
                            handler.onPartialThinking(new dev.langchain4j.model.chat.response.PartialThinking("unfinished-thinking-progress-marker"));
                        ChatResponse response = next(request);
                        var calls = response.aiMessage().toolExecutionRequests();
                        if (calls != null) for (int i = 0; i < calls.size(); i++)
                            handler.onCompleteToolCall(new CompleteToolCall(i, calls.get(i)));
                        handler.onCompleteResponse(response);
                    } catch (Throwable error) { handler.onError(error); }
                });
            }
        };
        Scenario(int rounds) { this.rounds = rounds; }
        ChatResponse next(ChatRequest request) {
            observed.set(request);
            int round = requests.incrementAndGet();
            if (round == failRequest) throw new IllegalArgumentException("Failed to buffer the request body: length limit exceeded");
            if (round > rounds) return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
            List<ToolExecutionRequest> calls = new ArrayList<>();
            for (int i = 0; i < batch; i++) calls.add(ToolExecutionRequest.builder()
                .id("call_" + round + "_" + i).name(TOOL.name()).arguments("{}").build());
            return ChatResponse.builder().aiMessage(AiMessage.from(calls)).build();
        }
        AiToolService tools() {
            ToolExecutor tool = (request, memoryId) -> {
                executions.incrementAndGet();
                if (failTool) throw new IllegalStateException("synthetic tool failure");
                return result;
            };
            return proxy(AiToolService.class, (p,m,a) -> switch (m.getName()) {
                case "getToolsWithExecutors" -> Map.of(TOOL, tool);
                case "handleExecutionError", "handleArgumentError" -> {
                    handledErrors.incrementAndGet();
                    yield ToolErrorHandlerResult.text("handled synthetic tool error");
                }
                default -> null;
            });
        }
        ImageRenderingService images() {
            return proxy(ImageRenderingService.class, (p,m,a) -> {
                if (m.getName().equals("setLocalModel")) localModel.set((Boolean)a[0]);
                return null;
            });
        }
        @Override public void close() { executor.shutdownNow(); }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Map<String,Object> values = new HashMap<>();
        PrefService prefs = preferences(values);
        check(AssistantExecutionSettings.roundTrips(prefs) == 1000, "fresh default is 1000");

        // This uses the actual plugin index from the built/installed JAR, not a
        // manually constructed service or a test-only discovery annotation.
        try (Context context = new Context(CommandService.class, UIService.class,
            org.scijava.task.TaskService.class, org.scijava.platform.PlatformService.class,
            AssistantService.class)) {
            AssistantService selected = context.service(AssistantService.class);
            check(selected instanceof ProviderAssistantService, "real SciJava selects extension service");
            check(get(selected, "providers") == context.service(ProviderService.class) &&
                get(selected, "tools") == context.service(AiToolService.class) &&
                get(selected, "images") == context.service(ImageRenderingService.class), "actual service dependencies injected");
            for (String name : List.of("DeepSeek", "SiliconFlow", "Volcengine", "NVIDIA",
                "ChatGPT", "Claude", "Gemini", "Ollama (Custom)"))
                check(context.service(ProviderService.class).getProvider(name) != null, "provider instantiated: " + name);
            check(context.service(PluginService.class).getPluginsOfType(org.scijava.service.Service.class)
                .stream().filter(p -> p.getClassName().equals(ProviderAssistantService.class.getName())).count() == 1,
                "only one execution override registered");
            check(sc.fiji.llm.ui.FijiAssistantChat.class.getDeclaredField("assistantService").getType()
                == AssistantService.class, "installed chat UI injects service interface");
            var command = context.service(CommandService.class).getCommand(ConfigureAssistantExecution.class);
            check(command != null && command.getMenuPath().getMenuString().endsWith("LLM Execution Settings..."),
                "settings command is discoverable");
            check(context.service(CommandService.class).getCommand(ShowContextStatus.class) != null,
                "context monitoring command is discoverable");
            ProviderAssistantService service = (ProviderAssistantService) selected;

            // Reproduce the exact existing limit with the installed core service.
            try (Scenario scenario = new Scenario(150)) {
                DefaultAssistantService original = new DefaultAssistantService();
                wire(original, newProvider("DeepSeek", scenario), scenario, prefs);
                Throwable error = streamFailure(original.createAssistant(Assistant.class, "DeepSeek", "fixture", null, null));
                check(error.getMessage().contains("exceeded 100 tool calling round trips"), "original 100-round failure reproduced");
                check(scenario.executions.get() == 100, "original stops after 100 executed rounds");
            }

            for (String name : List.of("DeepSeek", "SiliconFlow", "Volcengine", "NVIDIA")) {
                try (Scenario scenario = new Scenario(150)) {
                    wire(service, newProvider(name, scenario), scenario, prefs);
                    ChatMemory memory = MessageWindowChatMemory.withMaxMessages(2000);
                    Assistant assistant = service.createAssistant(Assistant.class, name, "fixture", memory, null);
                    check("done".equals(stream(assistant)), name + " completes beyond old 100-round cap");
                    check(scenario.executions.get() == 150 && scenario.requests.get() == 151,
                        name + " actual model/tool round count");
                    check(memory.messages().stream().anyMatch(m -> m instanceof ToolExecutionResultMessage),
                        name + " uses the supplied conversation memory");
                    check(Boolean.FALSE.equals(scenario.localModel.get()), name + " hosted image access policy retained");
                    scenario.requests.set(0); scenario.executions.set(0);
                    check("done".equals(stream(assistant)), name + " new user message gets a fresh round budget");
                    check(scenario.executions.get() == 150, name + " budget is per message, not lifetime");
                }
                try (Scenario scenario = new Scenario(150)) {
                    wire(service, newProvider(name, scenario), scenario, prefs);
                    check("done".equals(service.createAssistant(Assistant.class, name, "fixture", null, null).chat("test")),
                        name + " synchronous loop also exceeds 100");
                }
            }
            try (Scenario scenario = new Scenario(1000)) {
                wire(service, newProvider("DeepSeek", scenario), scenario, prefs);
                check("done".equals(stream(service.createAssistant(Assistant.class, "DeepSeek", "fixture", null, null))),
                    "stream finishes after exactly 1000 tool rounds");
                check(scenario.executions.get() == 1000, "1000 tools were actually executed");
            }
            try (Scenario scenario = new Scenario(1001)) {
                wire(service, newProvider("DeepSeek", scenario), scenario, prefs);
                Throwable error = streamFailure(service.createAssistant(Assistant.class, "DeepSeek", "fixture", null, null));
                check(error.getMessage().contains("exceeded 1000 tool calling round trips"), "finite 1000-round backstop retained");
                check(scenario.executions.get() == 1000, "1001st tool round is not executed");
            }

            AssistantExecutionSettings.save(prefs, 3);
            try (Scenario scenario = new Scenario(3)) {
                scenario.batch = 2;
                wire(service, newProvider("NVIDIA", scenario), scenario, prefs);
                check("done".equals(stream(service.createAssistant(Assistant.class, "NVIDIA", "fixture", null, null))),
                    "saved custom cap applied to newly built assistant");
                check(scenario.executions.get() == 6, "two tools per round still count as one round");
            }
            try (Scenario scenario = new Scenario(4)) {
                wire(service, newProvider("NVIDIA", scenario), scenario, prefs);
                check(streamFailure(service.createAssistant(Assistant.class, "NVIDIA", "fixture", null, null))
                    .getMessage().contains("exceeded 3 tool calling round trips"), "custom cap enforced");
                check(scenario.executions.get() == 3, "no execution beyond custom cap");
            }
            values.clear();
            verifyTransformsAndErrors(service, prefs);
            verifySettings(context, service, prefs, values);
            values.clear();

            // An unrelated provider delegates to the installed core. A custom
            // limit must never silently change built-in ChatGPT/Claude/Ollama.
            try (Scenario scenario = new Scenario(101)) {
                LLMProvider foreign = new AbstractLLMProvider() {
                    public String getName() { return "Unrelated"; }
                    public boolean requiresApiKey() { return false; }
                    public String getDescription() { return "Synthetic unrelated provider"; }
                    public String getModelsDocumentationUrl() { return "https://example.invalid/"; }
                    public java.time.LocalDate getModelsDocumentationLastModified() { return java.time.LocalDate.of(2026, 11, 2); }
                    public String getApiKeyUrl() { return "https://example.invalid/"; }
                    public List<String> getAvailableModels() { return List.of("fixture"); }
                    public ChatModel createChatModel(String id) { return scenario.sync; }
                    public StreamingChatModel createStreamingChatModel(String id) { return scenario.streaming; }
                    public dev.langchain4j.memory.chat.TokenWindowChatMemory createTokenChatMemory(String id) {
                        return new DeepSeekProvider().createTokenChatMemory(id);
                    }
                };
                wire(service, foreign, scenario, prefs);
                check(streamFailure(service.createAssistant(Assistant.class, "Unrelated", "fixture", null, null))
                    .getMessage().contains("exceeded 100 tool calling round trips"), "other providers retain core behavior");
                check(Boolean.TRUE.equals(scenario.localModel.get()), "local-provider image policy retained by core delegation");
            }
        }
        System.out.println("PASS: real service selection; old 100-round failure; four providers >100 sync/stream; 1000 completes/1001 stops; per-message reset; custom cap; image/memory/parameters/errors; settings and core fallback");
    }

    static void verifyTransformsAndErrors(ProviderAssistantService service, PrefService prefs) throws Exception {
        for (boolean nativeImages : List.of(false, true)) {
            try (Scenario scenario = new Scenario(0)) {
                scenario.nativeImages = nativeImages;
                var provider = newProvider("DeepSeek", scenario);
                wire(service, provider, scenario, prefs);
                ImageContent image = ImageContent.from(PNG, "image/png");
                ChatMemory memory = MessageWindowChatMemory.withMaxMessages(20);
                var call = ToolExecutionRequest.builder().id("image").name(TOOL.name()).arguments("{}").build();
                memory.add(UserMessage.from("image test"));
                memory.add(AiMessage.from(call));
                memory.add(ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name())
                    .contents(List.of(TextContent.from("preview"), image)).build());
                var defaults = ChatRequestParameters.builder().temperature(0.25).maxOutputTokens(127).build();
                stream(service.createAssistant(Assistant.class, "DeepSeek", "fixture", memory, defaults));
                ChatRequest request = scenario.observed.get();
                check(request.parameters().temperature().equals(0.25) && request.parameters().maxOutputTokens() == 127,
                    "default request parameters preserved");
                check(request.messages().stream().anyMatch(m -> m instanceof ToolExecutionResultMessage t &&
                    t.contents().contains(image)) == nativeImages, "native tool image decision preserved");
                if (!nativeImages) check(request.messages().stream().anyMatch(m -> m instanceof UserMessage u &&
                    u.contents().contains(image)), "normalizer retains exact image bytes in user content");
            }
        }
        try (Scenario scenario = new Scenario(1)) {
            scenario.failTool = true;
            wire(service, newProvider("DeepSeek", scenario), scenario, prefs);
            check("done".equals(stream(service.createAssistant(Assistant.class, "DeepSeek", "fixture", null, null))),
                "core tool error handler permits model recovery");
            check(scenario.handledErrors.get() == 1, "core tool error handler invoked");
            check(scenario.observed.get().messages().stream().anyMatch(m -> m instanceof ToolExecutionResultMessage t &&
                t.text().contains("handled synthetic tool error")), "tool diagnostic reaches next model turn");
        }
    }

    static void verifySettings(Context context, ProviderAssistantService service, PrefService prefs,
        Map<String,Object> values) throws Exception
    {
        var messages = new ArrayList<String>();
        UIService ui = proxy(UIService.class, (p,m,a) -> {
            if (m.getName().equals("showDialog")) messages.add(String.valueOf(a[0]));
            return null;
        });
        var module = context.service(CommandService.class).getCommand(ConfigureAssistantExecution.class).createModule();
        ConfigureAssistantExecution command = (ConfigureAssistantExecution) module.getDelegateObject();
        set(command, ConfigureAssistantExecution.class, "assistantService", service);
        set(command, ConfigureAssistantExecution.class, "prefs", prefs);
        set(command, ConfigureAssistantExecution.class, "ui", ui);
        module.initialize();
        check(get(command, "status").toString().endsWith("ACTIVE"), "settings reports active service");
        set(command, ConfigureAssistantExecution.class, "maxToolCallingRoundTrips", 410);
        set(command, ConfigureAssistantExecution.class, "maxInputTokens", 524288);
        set(command, ConfigureAssistantExecution.class, "maxRequestMiB", 40);
        set(command, ConfigureAssistantExecution.class, "outputReserveTokens", 32768);
        command.run();
        check(AssistantExecutionSettings.roundTrips(preferences(values)) == 410, "saved value read through stable preference namespace");
        check(AssistantExecutionSettings.inputTokens(prefs) == 524288 &&
            AssistantExecutionSettings.requestMiB(prefs) == 40 &&
            AssistantExecutionSettings.outputReserve(prefs) == 32768, "context UI settings persist across reconstruction");
        set(command, ConfigureAssistantExecution.class, "maxToolCallingRoundTrips", 0);
        command.run();
        check(AssistantExecutionSettings.roundTrips(prefs) == 410, "invalid setting does not overwrite previous value");
        set(command, ConfigureAssistantExecution.class, "assistantService", new DefaultAssistantService());
        command.initialize(); command.run();
        check(messages.get(messages.size() - 1).contains("not active"), "update/service conflict is visible");
        check(AssistantExecutionSettings.roundTrips(prefs) == 410, "inactive override does not claim to save an effective setting");
        set(command, ConfigureAssistantExecution.class, "assistantService", service);
        set(command, ConfigureAssistantExecution.class, "maxToolCallingRoundTrips", 500);
        command.cancel("simulated cancellation"); command.run();
        check(AssistantExecutionSettings.roundTrips(prefs) == 410, "cancel does not change settings");
        values.put(AssistantExecutionSettings.ROUND_TRIPS_KEY, -1);
        check(AssistantExecutionSettings.roundTrips(prefs) == 1000, "invalid stored limit falls back to finite default");
    }

    static LLMProvider newProvider(String name, Scenario s) {
        return switch (name) {
            case "DeepSeek" -> new DeepSeekProvider() {
                public ChatModel createChatModel(String id) { return s.sync; }
                public StreamingChatModel createStreamingChatModel(String id) { return s.streaming; }
                public boolean supportsImageToolResults(String id) { return s.nativeImages; }
            };
            case "SiliconFlow" -> new SiliconFlowProvider() {
                public ChatModel createChatModel(String id) { return s.sync; }
                public StreamingChatModel createStreamingChatModel(String id) { return s.streaming; }
            };
            case "Volcengine" -> new VolcengineProvider() {
                public ChatModel createChatModel(String id) { return s.sync; }
                public StreamingChatModel createStreamingChatModel(String id) { return s.streaming; }
            };
            case "NVIDIA" -> new NvidiaProvider() {
                public ChatModel createChatModel(String id) { return s.sync; }
                public StreamingChatModel createStreamingChatModel(String id) { return s.streaming; }
            };
            default -> throw new IllegalArgumentException(name);
        };
    }

    static void wire(DefaultAssistantService service, LLMProvider provider, Scenario s, PrefService prefs) throws Exception {
        ProviderService providers = proxy(ProviderService.class, (p,m,a) -> m.getName().equals("getProvider") ? provider : null);
        var tools = s.tools(); var images = s.images();
        set(service, DefaultAssistantService.class, "providerService", providers);
        set(service, DefaultAssistantService.class, "aiToolService", tools);
        set(service, DefaultAssistantService.class, "imageRenderingService", images);
        if (service instanceof ProviderAssistantService) {
            set(service, ProviderAssistantService.class, "providers", providers);
            set(service, ProviderAssistantService.class, "tools", tools);
            set(service, ProviderAssistantService.class, "images", images);
            set(service, ProviderAssistantService.class, "prefs", prefs);
        }
    }
    static CompletableFuture<ChatResponse> future(Assistant assistant) {
        var result = new CompletableFuture<ChatResponse>();
        assistant.stream("continue the test").onPartialResponse(s -> {})
            .onCompleteResponse(result::complete).onError(result::completeExceptionally).start();
        return result;
    }
    static String stream(Assistant assistant) throws Exception {
        return future(assistant).get(15, TimeUnit.SECONDS).aiMessage().text();
    }
    static Throwable streamFailure(Assistant assistant) throws Exception {
        try { stream(assistant); throw new AssertionError("Expected tool-round limit failure"); }
        catch (java.util.concurrent.ExecutionException expected) { return expected.getCause(); }
    }
    static PrefService preferences(Map<String,Object> values) {
        return proxy(PrefService.class, (p,m,a) -> {
            if (m.getName().equals("getInt")) return values.getOrDefault((String)a[1], a[2]);
            if (m.getName().equals("put")) { values.put((String)a[1], a[2]); return null; }
            return null;
        });
    }
    static void set(Object object, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }
    static Object get(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); }
}
