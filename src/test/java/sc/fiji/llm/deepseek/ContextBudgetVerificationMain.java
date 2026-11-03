/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.http.client.sse.*;
import dev.langchain4j.model.chat.request.ChatRequest;

/** Byte-boundary, real HTTP and real assistant-loop checks, without credentials. */
public final class ContextBudgetVerificationMain {
    public static void main(String[] args) throws Exception {
        String unicode = "图像😀";
        int bytes = unicode.getBytes(StandardCharsets.UTF_8).length;
        RequestBudget.checkBytes(unicode, bytes);
        rejects(() -> RequestBudget.checkBytes(unicode, bytes - 1), "request bytes");
        var original = List.<ChatMessage>of(UserMessage.from("Do not lose the current task"));
        var request = ChatRequest.builder().messages(original).build();
        rejects(() -> RequestBudget.checkTokens(request, 1), "estimated input tokens");
        long exact = RequestBudget.estimatedTokens(request);
        RequestBudget.checkTokens(request, exact);
        rejects(() -> RequestBudget.checkTokens(request, exact - 1), "estimated input tokens");
        check(request.messages().equals(original), "preflight leaves original messages intact");
        var prefs = AssistantExecutionVerificationMain.preferences(new HashMap<>());
        check(AssistantExecutionSettings.inputTokens(prefs) == 0, "automatic context default");
        check(AssistantExecutionSettings.requestMiB(prefs) == 0, "automatic provider byte default");
        AssistantExecutionSettings.save(prefs, 123, 4096, 8);
        rejects(() -> AssistantExecutionSettings.save(prefs, 999, -1, 9), "positive");
        check(AssistantExecutionSettings.roundTrips(prefs) == 123 &&
            AssistantExecutionSettings.inputTokens(prefs) == 4096 &&
            AssistantExecutionSettings.requestMiB(prefs) == 8, "invalid save is atomic");
        var manualValues = new HashMap<String,Object>();
        manualValues.put(AssistantExecutionSettings.INPUT_TOKENS_KEY, 1_048_576);
        var manualPrefs = AssistantExecutionVerificationMain.preferences(manualValues);
        var unknownCatalog = new SiliconFlowProvider() {
            @Override protected org.scijava.prefs.PrefService preferences() { return manualPrefs; }
            @Override protected java.nio.file.Path modelCacheFile() { return null; }
        };
        check(unknownCatalog.contextWindow("unknown-metadata-model") == 1_048_576,
            "manual 1M window works when the provider omits context metadata");

        // Both real protocols, sync and SSE: no rejected body reaches the server.
        var received = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            received.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new BudgetHttpClientBuilder(new JdkHttpClientBuilder(), 128).build();
            for (String path : List.of("/responses", "/chat/completions")) {
                String url = "http://127.0.0.1:" + server.getAddress().getPort() + path;
                var allowed = HttpRequest.builder().method(HttpMethod.POST).url(url).body("x".repeat(128)).build();
                client.execute(allowed);
                var blocked = HttpRequest.builder().method(HttpMethod.POST).url(url)
                    .body("{\"image_base64\":\"" + "A".repeat(129) + "\",\"reasoning\":\"opaque\"}").build();
                rejects(() -> client.execute(blocked), "request bytes");
                rejects(() -> client.execute(blocked, new DefaultServerSentEventParser(),
                    new ServerSentEventListener() {
                        @Override public void onEvent(ServerSentEvent event) {}
                        @Override public void onError(Throwable error) {}
                    }), "request bytes");
            }
            check(received.get() == 2, "oversized sync/SSE requests never transmitted");
            for (AbstractCatalogProvider provider : List.of(new DeepSeekProvider(),
                new SiliconFlowProvider(), new VolcengineProvider(), new NvidiaProvider())) {
                var providerClient = provider.httpClientBuilder().build();
                var oversized = HttpRequest.builder().method(HttpMethod.POST)
                    .url("http://127.0.0.1:" + server.getAddress().getPort() + "/")
                    .body("A".repeat((int)provider.requestByteLimit() + 1)).build();
                rejects(() -> providerClient.execute(oversized), "request bytes");
            }
            check(received.get() == 2, "all four provider factories enforce the byte default");
        } finally { server.stop(0); }

        // The real AI-service transformer applies again after tool results.
        for (boolean streaming : List.of(false, true)) {
            var values = new HashMap<String,Object>();
            values.put(AssistantExecutionSettings.INPUT_TOKENS_KEY, 300);
            var loopPrefs = AssistantExecutionVerificationMain.preferences(values);
            try (var scenario = new AssistantExecutionVerificationMain.Scenario(80)) {
                var service = new ProviderAssistantService();
                AssistantExecutionVerificationMain.wire(service,
                    AssistantExecutionVerificationMain.newProvider("DeepSeek", scenario), scenario, loopPrefs);
                var memory = dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(1000);
                var assistant = service.createAssistant(AssistantExecutionVerificationMain.Assistant.class,
                    "DeepSeek", "fixture", memory, null);
                if (streaming) {
                    String paused = AssistantExecutionVerificationMain.stream(assistant);
                    check(paused.contains("Task paused") && paused.contains("checkpoint"), "streaming loop checkpoint");
                } else rejects(() -> assistant.chat("continue the test"), "cannot fit safely");
                check(scenario.executions.get() > 0 && scenario.executions.get() < 80, "stops growing tool context");
                check(memory.messages().stream().anyMatch(m -> m instanceof ToolExecutionResultMessage),
                    "rejected request does not delete tool results");
            }
        }
        verifyAutomaticContinuation();
        verifyRecoverableFailure();
        System.out.println("PASS: automatic context settings; UTF-8 boundaries; four provider HTTP guards; automatic compaction; archived full results; visible pause checkpoint and same-chat continuation");
    }

    static void verifyAutomaticContinuation() throws Exception {
        var prefs = AssistantExecutionVerificationMain.preferences(new HashMap<>());
        for (boolean streaming : List.of(false, true))
            try (var scenario = new AssistantExecutionVerificationMain.Scenario(20)) {
                scenario.result = "full macro result marker; a=1; ".repeat(5000);
                var memory = dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(1000);
                var service = new ProviderAssistantService();
                AssistantExecutionVerificationMain.wire(service,
                    AssistantExecutionVerificationMain.newProvider("DeepSeek", scenario), scenario, prefs);
                var assistant = service.createAssistant(AssistantExecutionVerificationMain.Assistant.class,
                    "DeepSeek", "fixture", memory, null);
                String text = streaming ? AssistantExecutionVerificationMain.stream(assistant) : assistant.chat("perform original task marker");
                check(text.equals("done"), "oversized tool results compacted automatically");
                check(scenario.executions.get() == 20, "no tools repeated by compaction");
                check(memory.messages().size() < 42, "working memory has actually been compacted");
                check(scenario.observed.get().messages().stream().anyMatch(m -> m instanceof ToolExecutionResultMessage t &&
                    t.hasSingleText() && t.text().contains("EXCERPT ONLY")), "latest large tool output identifies its archive");
            }
        var context = new BoundedContext(24000, 1_048_576);
        var goal = UserMessage.from("Original goal that must survive compaction");
        var oldCall = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("old").name("capture").arguments("{}").build();
        var newCall = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("new").name("measure").arguments("{}").build();
        var image = ImageContent.from("A".repeat(1000000), "image/png");
        var full = ToolExecutionResultMessage.builder().id("old").toolName("capture").contents(List.of(image)).build();
        var request = ChatRequest.builder().messages(List.of(goal, AiMessage.from(oldCall), full,
            AiMessage.from(newCall), ToolExecutionResultMessage.from(newCall, "verified data marker"))).build();
        var compact = context.prepare(request);
        check(compact.messages().contains(goal), "original task retained intact");
        check(!compact.messages().contains(full), "old image offloaded from working context");
        String saved = java.nio.file.Files.readString(context.archive.directory.resolve(context.archive.store(full)));
        check(ChatMessageDeserializer.messageFromJson(saved).equals(full), "archived image round-trips exactly");
        check(compact.messages().stream().anyMatch(m -> m instanceof ToolExecutionResultMessage t &&
            t.id().equals("new") && t.text().contains("verified data marker")), "latest tool result retained intact");
        check(java.nio.file.Files.exists(context.archive.directory.resolve("checkpoint.md")), "checkpoint saved before continuing");
    }

    static void verifyRecoverableFailure() throws Exception {
        try (var scenario = new AssistantExecutionVerificationMain.Scenario(6)) {
            scenario.failRequest = 4;
            var memory = dev.langchain4j.memory.chat.MessageWindowChatMemory.withMaxMessages(1000);
            memory.add(UserMessage.from("original-nuclei-task-marker"));
            var service = new ProviderAssistantService();
            AssistantExecutionVerificationMain.wire(service,
                AssistantExecutionVerificationMain.newProvider("DeepSeek", scenario), scenario,
                AssistantExecutionVerificationMain.preferences(new HashMap<>()));
            var assistant = service.createAssistant(AssistantExecutionVerificationMain.Assistant.class,
                "DeepSeek", "fixture", memory, null);
            var visible = new StringBuilder();
            var panel = new sc.fiji.llm.ui.ChatMessagePanel(
                sc.fiji.llm.ui.ChatMessagePanel.MessageType.ASSISTANT, "");
            var conversation = new sc.fiji.llm.chat.Conversation("fixture", "Context recovery test", SystemMessage.from("test"));
            var complete = new java.util.concurrent.CompletableFuture<dev.langchain4j.model.chat.response.ChatResponse>();
            var errors = new AtomicInteger();
            assistant.stream("continue")
                .beforeToolExecution(tool -> javax.swing.SwingUtilities.invokeLater(() ->
                    panel.toolStarted(tool.request().name(), tool.request().arguments())))
                .onToolExecuted(tool -> javax.swing.SwingUtilities.invokeLater(() ->
                    panel.toolFinished(tool.request().name(), tool.hasFailed(), tool.duration().toMillis(), "result")))
                .onPartialResponseWithContext((part, ctx) -> {
                    visible.append(part.text());
                    javax.swing.SwingUtilities.invokeLater(() -> panel.appendText(part.text()));
                })
                .onPartialThinkingWithContext((part, ctx) ->
                    javax.swing.SwingUtilities.invokeLater(() -> panel.appendThinking(part.text())))
                .onCompleteResponse(response -> {
                    try {
                        javax.swing.SwingUtilities.invokeAndWait(() -> {
                            panel.finishWorking();
                            conversation.addMessage(panel.getText(), response.aiMessage(), panel.getActivity());
                        });
                        complete.complete(response);
                    } catch (Exception failure) { complete.completeExceptionally(failure); }
                }).onError(error -> {errors.incrementAndGet(); complete.completeExceptionally(error);}).start();
            var paused = complete.get(15, java.util.concurrent.TimeUnit.SECONDS).aiMessage().text();
            check(paused.contains("Task paused") && visible.toString().contains("original-nuclei-task-marker"), "checkpoint visible through Fiji's actual callback API");
            check(errors.get() == 0 && scenario.executions.get() == 3, "context failure bypasses destructive UI error rollback");
            check(panel.getText().contains("Task paused") && !panel.getActivity().isEmpty() &&
                conversation.messages().size() == 1, "real Fiji chat panel retains progress and saves paused assistant response");
            String checkpointPath = paused.lines().filter(line -> line.startsWith("Progress checkpoint: "))
                .findFirst().orElseThrow().substring("Progress checkpoint: ".length());
            String events = java.nio.file.Files.readString(java.nio.file.Path.of(checkpointPath)
                .resolveSibling("stream-events.jsonl"));
            check(events.contains("unfinished-thinking-progress-marker"), "partial thinking survives a failed model stream on disk");
            check(memory.messages().stream().anyMatch(m -> m instanceof AiMessage a && String.valueOf(a.text()).contains("Task paused")), "checkpoint remains in conversation memory");
            scenario.failRequest = -1;
            check(AssistantExecutionVerificationMain.stream(assistant).equals("done"), "same chat accepts continuation");
            check(scenario.executions.get() == 5, "continuation does not rerun previous three tools");
        }
    }
    static void rejects(Runnable action, String text) {
        try { action.run(); throw new AssertionError("Expected rejection: " + text); }
        catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains(text), "actionable rejection: " + text);
        }
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
