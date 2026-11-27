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
        int loopFixtureWindow;
        try(var probe=new AssistantExecutionVerificationMain.Scenario(0)) {
            var service=new ProviderAssistantService();
            AssistantExecutionVerificationMain.wire(service,AssistantExecutionVerificationMain.newProvider("DeepSeek",probe),probe,
                AssistantExecutionVerificationMain.preferences(new HashMap<>()));
            service.createAssistant(AssistantExecutionVerificationMain.Assistant.class,"DeepSeek","fixture",null,null).chat("continue the test");
            // Size only the fixture's header from an actual transformed request.
            // Then leave deliberately little room for real subsequent tool work.
            loopFixtureWindow=(int)Math.ceil((RequestBudget.estimatedTokens(probe.observed.get())+200)/0.75);
        }
        for (boolean streaming : List.of(false, true)) {
            var values = new HashMap<String,Object>();
            // Leave space for the reporting-tool presentation instruction, then
            // exercise actual growth across executed tools before compaction.
            values.put(AssistantExecutionSettings.INPUT_TOKENS_KEY, loopFixtureWindow);
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
        System.out.println("PASS: automatic context settings; UTF-8 boundaries; four provider HTTP guards; automatic compaction; archived full results; long lists of very large tool results shortened with counts, text untouched; visible pause checkpoint and same-chat continuation");
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
        longLists();
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

    /** Two real chats carried 520,000 and 550,000 characters of ROI rows through every later request. */
    static void longLists() throws Exception {
        var rows = new StringBuilder("{\"present\":true,\"count\":4311,\"rois\":[");
        for (int n = 0; n < 4311; n++) rows.append(n == 0 ? "" : ",").append("{\"roi_index\":").append(n)
            .append(",\"name\":\"Cellpose-4_").append(n).append("\",\"selected\":false,\"type\":\"Polygon\",\"bounds\":{\"x\":21,\"y\":0,\"width\":13,\"height\":19}}");
        rows.append("],\"histogram\":[");
        for (int n = 0; n < 40; n++) rows.append(n == 0 ? "" : ",").append(n);
        rows.append("],\"columns\":[\"a\",\"b\"]}");
        check(rows.length() > 400_000, "fixture is as large as the real result");
        var roiCall = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("rois").name("fiji_rois_read").arguments("{}").build();
        var textCall = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("text").name("fiji_script_read_content").arguments("{}").build();
        var smallCall = dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id("small").name("fiji_results_read").arguments("{}").build();
        var big = ToolExecutionResultMessage.from(roiCall, rows.toString());
        var script = ToolExecutionResultMessage.from(textCall, "print(1);\n".repeat(9000));
        var small = ToolExecutionResultMessage.from(smallCall, "{\"rows\":[" + "1,".repeat(300) + "1]}");
        var request = ChatRequest.builder().messages(List.of(UserMessage.from("count the cells"),
            AiMessage.from(roiCall, textCall, smallCall), big, script, small)).build();
        var context = new BoundedContext(900_000, 48 << 20);
        var sent = context.prepare(request);
        java.util.function.Function<String, ToolExecutionResultMessage> result = id -> (ToolExecutionResultMessage) sent.messages().stream()
            .filter(m -> m instanceof ToolExecutionResultMessage t && t.id().equals(id)).findFirst().orElseThrow();
        var got = result.apply("rois");
        var json = com.google.gson.JsonParser.parseString(got.text()).getAsJsonObject();
        var list = json.getAsJsonArray("rois");
        check(got.toolName().equals("fiji_rois_read") && got.text().length() < 6000, "the long list no longer travels with every request: " + got.text().length());
        check(list.size() == LongLists.KEEP + 1 && list.get(0).getAsJsonObject().get("name").getAsString().equals("Cellpose-4_0")
            && list.get(19).getAsJsonObject().get("roi_index").getAsInt() == 19, "the first rows are kept as they were");
        var mark = list.get(LongLists.KEEP).getAsJsonObject();
        check(mark.get("rows_omitted_here").getAsInt() == 4291 && mark.get("rows_in_total").getAsInt() == 4311, "the cut list says how many rows it had");
        check(json.get("count").getAsInt() == 4311 && json.get("present").getAsBoolean() && json.getAsJsonArray("columns").size() == 2
            && json.getAsJsonArray("histogram").size() == 40, "every other field, and a list that did not need cutting, is complete");
        String stored = context.archive.store(big);
        check(json.get("result_shortened").getAsString().contains(stored) && json.get("result_shortened").getAsString().contains("not all rows"),
            "the result says it was shortened and where the full one is");
        check(ChatMessageDeserializer.messageFromJson(java.nio.file.Files.readString(context.archive.directory.resolve(stored))).equals(big),
            "the archive keeps the full result");
        check(result.apply("text").equals(script) && result.apply("small").equals(small), "long text and ordinary results are sent unchanged");
        check(context.prepare(sent).messages().equals(sent.messages()), "a shortened result is not shortened again");
        context.shortenLists = false;
        check(context.prepare(request).messages().contains(big), "switched off, the result is sent in full");
        check(LongLists.shorten("x".repeat(200_000), "f") == null && LongLists.shorten("\"" + "x".repeat(200_000) + "\"", "f") == null
            && LongLists.shorten("{\"log\":\"" + "x".repeat(200_000) + "\"}", "f") == null, "text is never cut, inside JSON or not");
        var top = new StringBuilder("[");
        for (int n = 0; n < 20000; n++) top.append(n == 0 ? "" : ",").append("[").append(n).append(",2,3]");
        var cutTop = com.google.gson.JsonParser.parseString(LongLists.shorten(top.append("]").toString(), "")).getAsJsonArray();
        check(cutTop.size() == LongLists.KEEP + 2 && cutTop.get(LongLists.KEEP + 1).getAsJsonObject().has("result_shortened"), "a result that is itself a list is cut the same way");
        check(AssistantExecutionSettings.shortenLists(null), "shortening is on unless switched off");
        // A real 1785-row table of 59,247 characters stayed just under the first limit of 60,000 and travelled with 99 requests.
        var table = new StringBuilder("{\"title\":\"Results\",\"row_count\":1785,\"columns\":[\"Area\",\"Mean\"],\"column_count\":2,\"rows\":[");
        for (int n = 0; n < 1785; n++) table.append(n == 0 ? "" : ",").append("{\"Area\":").append(100 + n).append(".0,\"Mean\":60.273631}");
        table.append("]}");
        String cutTable = LongLists.shorten(table.toString(), "f");
        check(table.length() > 50_000 && table.length() < 60_000 && cutTable != null && cutTable.length() < 3000
            && com.google.gson.JsonParser.parseString(cutTable).getAsJsonObject().get("row_count").getAsInt() == 1785, "a table of that size is shortened: " + table.length());
        var controls = new StringBuilder("{\"controls\":[");
        for (int n = 0; n < 150; n++) controls.append(n == 0 ? "" : ",").append("{\"component_path\":\"root/child[").append(n).append("]/child[0]\",\"class\":\"javax.swing.JTextField\",\"label\":\"").append("x".repeat(60)).append("\"}");
        check(controls.append("]}").length() > 20_000 && controls.length() < LongLists.LIMIT && LongLists.shorten(controls.toString(), "f") == null,
            "a list of interface controls as large as the largest seen so far is sent whole: " + controls.length());
    }
}
