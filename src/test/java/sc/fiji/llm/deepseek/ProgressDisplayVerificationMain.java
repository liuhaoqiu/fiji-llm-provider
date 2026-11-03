/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.*;
import javax.swing.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;
import sc.fiji.llm.ui.ChatMessagePanel;

/** Real chat panel + explicit events before/after an actual context compaction. */
public final class ProgressDisplayVerificationMain {
    public interface Assistant { TokenStream stream(String prompt); }
    static final StreamingHandle HANDLE = new StreamingHandle() {
        public void cancel() {}
        public boolean isCancelled() { return false; }
    };

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        var context = new BoundedContext(24000, 1_048_576);
        var events = new HashMap<String,Object>();
        var calls = new AtomicInteger();
        var finished = new AtomicInteger();
        dev.langchain4j.invocation.InvocationContext invocation = dev.langchain4j.invocation.InvocationContext.builder()
            .invocationId(java.util.UUID.randomUUID()).interfaceName(Assistant.class.getName())
            .methodName("stream").methodArguments(List.of("start")).chatMemoryId("fixture").timestampNow().build();
        var old = ToolExecutionRequest.builder().id("before").name("inspect_before").arguments("{}").build();
        var recent = ToolExecutionRequest.builder().id("after").name("measure_after").arguments("{}").build();
        var request = ChatRequest.builder().messages(List.of(UserMessage.from("Count the original image"),
            AiMessage.from(old), ToolExecutionResultMessage.builder().id("before").toolName("inspect_before")
                .contents(List.of(TextContent.from("ARCHIVE_ONLY_DETAIL " + "previous debug record ".repeat(200)),
                    ImageContent.from("A".repeat(1000000), "image/png"))).build(),
            AiMessage.from(recent), ToolExecutionResultMessage.from(recent, "{\"count\":42,\"validation\":\"pending\"}"))).build();
        TokenStream stream = (TokenStream)Proxy.newProxyInstance(TokenStream.class.getClassLoader(),
            new Class<?>[]{TokenStream.class}, (proxy, method, values) -> {
                if (!method.getName().equals("start")) {
                    if (values != null && values.length > 0) events.put(method.getName(), values[0]);
                    return proxy;
                }
                var thinking = (BiConsumer<PartialThinking,PartialThinkingContext>) events.get("onPartialThinkingWithContext");
                thinking.accept(new PartialThinking("BEFORE_COMPACTION_MARKER"), new PartialThinkingContext(HANDLE));
                context.prepare(request); // routes a real compaction notice through the adapter
                for (int i=0;i<5;i++) {
                    thinking.accept(new PartialThinking(" AFTER_COMPACTION_MARKER_" + i), new PartialThinkingContext(HANDLE));
                    var tool = ToolExecutionRequest.builder().id("live_"+i).name("POST_COMPACTION_TOOL_"+i).arguments("{}").build();
                    ((Consumer<dev.langchain4j.service.tool.BeforeToolExecution>)events.get("beforeToolExecution"))
                        .accept(dev.langchain4j.service.tool.BeforeToolExecution.builder().request(tool).invocationContext(invocation).build());
                    ((Consumer<dev.langchain4j.service.tool.ToolExecution>)events.get("onToolExecuted"))
                        .accept(dev.langchain4j.service.tool.ToolExecution.builder().request(tool)
                            .result("POST_COMPACTION_RESULT_"+i).startTime(java.time.LocalDateTime.now())
                            .finishTime(java.time.LocalDateTime.now()).invocationContext(invocation).build());
                    check(finished.get()==0, "compaction must not prematurely complete the displayed reply");
                    calls.incrementAndGet();
                }
                ((BiConsumer<PartialResponse,PartialResponseContext>)events.get("onPartialResponseWithContext"))
                    .accept(new PartialResponse("FINAL_ANSWER_MARKER"), new PartialResponseContext(HANDLE));
                ((Consumer<ChatResponse>)events.get("onCompleteResponse"))
                    .accept(ChatResponse.builder().aiMessage(AiMessage.from("FINAL_ANSWER_MARKER")).build());
                return null;
            });
        Assistant wrapped = ProgressAssistant.wrap(Assistant.class, prompt -> stream, context, null);
        var panel = new ChatMessagePanel(ChatMessagePanel.MessageType.ASSISTANT, "");
        var log = new StringBuilder();
        try { SwingUtilities.invokeAndWait(() -> {
            panel.startWorking();
            wrapped.stream("start").onPartialThinkingWithContext((part, ctx) -> {
                log.append(part.text()); panel.appendThinking(part.text());
            }).beforeToolExecution(tool -> panel.toolStarted(tool.request().name(),tool.request().arguments()))
                .onToolExecuted(tool -> panel.toolFinished(tool.request().name(),tool.hasFailed(),tool.duration().toMillis(),tool.result()))
                .onPartialResponseWithContext((part, ctx) -> panel.appendText(part.text()))
                .onCompleteResponse(response -> { finished.incrementAndGet(); panel.finishWorking(); })
                .onError(error -> {throw new AssertionError(error);}).start();
        }); } catch (Exception failure) {
            SwingUtilities.invokeAndWait(panel::finishWorking);
            throw failure;
        }
        if (args.length > 0 && args[0].equals("baseline")) {
            check(panel.getText().contains("ARCHIVE_ONLY_DETAIL") && !log.toString().contains("ARCHIVE_ONLY_DETAIL"),
                "0.5.2 reproduction: checkpoint detail is placed in the answer pane, away from the live activity log");
            System.out.println("REPRODUCED: 0.5.2 checkpoint detail displaces the live activity display while later thinking callbacks still arrive");
            return;
        }
        check(!panel.getText().contains("ARCHIVE_ONLY_DETAIL"), "checkpoint internals must not occupy the answer pane");
        check(log.toString().contains("ARCHIVE_ONLY_DETAIL"), "full checkpoint detail stays in the updating activity log");
        for(int i=0;i<5;i++) check(log.toString().contains("AFTER_COMPACTION_MARKER_"+i), "post-compaction thinking keeps arriving");
        check(panel.getText().contains("FINAL_ANSWER_MARKER") && finished.get()==1 && calls.get()==5,
            "stage notices do not complete the stream or suppress its final answer");
        check(panel.getText().contains("42") && panel.getText().contains("pending"),
            "short stage output reflects a returned result without asserting validation");
        check(!panel.getActivity().isEmpty(), "activity record retained after completion");
        check(panel.getActivity().getSteps().stream().filter(step -> !step.isThinking() &&
            step.getTool().startsWith("POST_COMPACTION_TOOL_")).count()==5, "post-compaction tool events remain in the displayed activity record");
        System.out.println("PASS: checkpoint channel isolation, concise stage output, post-compaction live thinking and final answer delivery in the original Fiji chat panel");
    }
    static void check(boolean value,String text) {if(!value)throw new AssertionError(text);}
}
