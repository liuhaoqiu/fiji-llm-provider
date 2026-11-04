/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.lang.reflect.*;
import java.util.function.*;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.BeforeToolExecution;
import dev.langchain4j.service.tool.ToolExecution;

/** Uses public TokenStream callbacks so Fiji saves a paused task as a visible
 * assistant checkpoint, rather than executing its failure rollback path.
 */
final class ProgressAssistant {
    private ProgressAssistant() {}
    // Fiji checks Stop in its body callback and preserves a nonempty progress
    // body there. Send a control-only empty display event while buffering a
    // model round. PartialResponse's constructor requires nonempty source text;
    // the public text() override deliberately exposes no body content. This
    // object is never sent to a model, archived, or stored in chat memory.
    private static final PartialResponse DISPLAY_CONTROL = new PartialResponse("display-control") {
        @Override public String text() { return ""; }
    };

    @SuppressWarnings("unchecked")
    static <T> T wrap(Class<T> type, T assistant, BoundedContext context, ChatMemory memory) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return invoke(method, assistant, args);
            context.cancelled = false;
            Object value;
            try { value = invoke(method, assistant, args); }
            catch (Throwable failure) {
                if ((!BoundedContext.isContextFailure(failure) && !BoundedContext.isTransportFailure(failure)) ||
                    method.getReturnType() != TokenStream.class) throw failure;
                final Consumer<Throwable>[] handler = new Consumer[]{null};
                value = Proxy.newProxyInstance(TokenStream.class.getClassLoader(), new Class<?>[]{TokenStream.class},
                    (streamProxy, operation, arguments) -> {
                        if (operation.getName().equals("onError")) handler[0] = (Consumer<Throwable>) arguments[0];
                        if (operation.getName().equals("start")) { handler[0].accept(failure); return null; }
                        return streamProxy;
                    });
            }
            return value instanceof TokenStream stream ? wrapStream(stream, context, memory) : value;
        }));
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    @SuppressWarnings("unchecked")
    private static TokenStream wrapStream(TokenStream delegate, BoundedContext context, ChatMemory memory) {
        class Callbacks implements InvocationHandler {
            Consumer<String> partial;
            BiConsumer<PartialResponse,PartialResponseContext> partialWithContext;
            Consumer<PartialThinking> thinking;
            BiConsumer<PartialThinking,PartialThinkingContext> thinkingWithContext;
            Consumer<ChatResponse> complete;
            Consumer<ChatResponse> intermediate;
            Consumer<Throwable> error;
            Consumer<BeforeToolExecution> beforeTool;
            Consumer<ToolExecution> afterTool;
            Consumer<PartialToolCall> partialTool;
            BiConsumer<PartialToolCall,PartialToolCallContext> partialToolWithContext;
            volatile StreamingHandle upstreamHandle;
            final AtomicBoolean terminal = new AtomicBoolean();
            final StreamActivity activity = new StreamActivity(this::emitWait);
            final StagePresentation presentation = new StagePresentation(text -> {
                context.archive.delta("display", text); emit(text);
            });
            boolean waitShown;
            final StreamingHandle handle = new StreamingHandle() {
                public void cancel() {
                    context.cancelled = true; activity.close();
                    if (upstreamHandle != null) upstreamHandle.cancel();
                }
                public boolean isCancelled() { return context.cancelled; }
            };
            void emit(String text) {
                if (partialWithContext != null)
                    partialWithContext.accept(new PartialResponse(text), new PartialResponseContext(handle));
                else if (partial != null) partial.accept(text);
            }
            void emitProgress(String detail) {
                context.archive.delta("checkpoint", detail);
                // A checkpoint is an activity event, not a large final answer.
                // Keep subsequent thinking/tool events in the same live log.
                if (thinkingWithContext != null)
                    thinkingWithContext.accept(new PartialThinking(detail), new PartialThinkingContext(handle));
                else if (thinking != null) thinking.accept(new PartialThinking(detail));
                // A short factual stage output remains visible in the answer
                // pane without pushing the activity area off-screen.
                presentation.stage("较早的上下文已整理，继续执行当前任务；完整记录保留在本地。 / Older context compacted; the current task continues. Full records remain local.");
            }
            void emitWait(String detail) {
                if (terminal.get() || context.cancelled) { activity.close(); return; }
                context.archive.delta("status", detail);
                if (thinkingWithContext != null)
                    thinkingWithContext.accept(new PartialThinking(detail), new PartialThinkingContext(handle));
                else if (thinking != null) thinking.accept(new PartialThinking(detail));
                if (!waitShown) {
                    waitShown = true;
                    presentation.stage("**插件状态 / Plugin status**：等待 API 输出或工具返回；尚无新的已确认进展。等待详情见运行日志。");
                }
            }
            void stopMonitoring() {
                activity.close(); context.requestStarting = () -> {};
                context.stageOutput = text -> {};
            }
            void failed(Throwable failure) {
                if (!terminal.compareAndSet(false, true)) return;
                stopMonitoring();
                if (!context.cancelled && (BoundedContext.isContextFailure(failure) || BoundedContext.isTransportFailure(failure))) {
                    String checkpoint = BoundedContext.isContextFailure(failure)
                        ? context.interrupted(failure) : context.transportInterrupted(failure);
                    AiMessage paused = AiMessage.from(checkpoint);
                    context.archive.store(paused);
                    if (memory != null) memory.add(paused);
                    presentation.finish(checkpoint, true);
                    if (complete != null) complete.accept(ChatResponse.builder().aiMessage(paused).build());
                } else if (error != null) error.accept(failure);
            }
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                switch (method.getName()) {
                    case "onPartialResponse": {
                        partial = (Consumer<String>) args[0];
                        delegate.onPartialResponse(text -> {
                            if (terminal.get()) return;
                            activity.output(); context.archive.delta("text", text); presentation.partial(text);
                        });
                        return proxy;
                    }
                    case "onPartialResponseWithContext":
                        partialWithContext = (BiConsumer<PartialResponse,PartialResponseContext>) args[0];
                        delegate.onPartialResponseWithContext((part, ctx) -> {
                            if (terminal.get()) return;
                            upstreamHandle = ctx.streamingHandle(); activity.output();
                            context.archive.delta("text", part.text()); presentation.partial(part.text());
                            partialWithContext.accept(DISPLAY_CONTROL, new PartialResponseContext(handle));
                        }); return proxy;
                    case "onPartialThinkingWithContext": {
                        thinkingWithContext = (BiConsumer<PartialThinking,PartialThinkingContext>) args[0];
                        delegate.onPartialThinkingWithContext((part, ctx) -> {
                            if (terminal.get()) return;
                            upstreamHandle = ctx.streamingHandle(); activity.output();
                            context.archive.delta("thinking", part.text()); thinkingWithContext.accept(part, ctx);
                        }); return proxy;
                    }
                    case "onPartialThinking": {
                        thinking = (Consumer<PartialThinking>) args[0];
                        delegate.onPartialThinking(part -> {
                            if (terminal.get()) return;
                            activity.output();
                            context.archive.delta("thinking", part.text()); thinking.accept(part);
                        }); return proxy;
                    }
                    case "onCompleteResponse": complete = (Consumer<ChatResponse>) args[0]; return proxy;
                    case "onIntermediateResponse": intermediate = (Consumer<ChatResponse>) args[0]; return proxy;
                    case "onError": error = (Consumer<Throwable>) args[0]; return proxy;
                    case "ignoreErrors": error = ignored -> {}; return proxy;
                    case "beforeToolExecution": beforeTool = (Consumer<BeforeToolExecution>) args[0]; return proxy;
                    case "onToolExecuted": afterTool = (Consumer<ToolExecution>) args[0]; return proxy;
                    case "onPartialToolCall": partialTool = (Consumer<PartialToolCall>) args[0]; return proxy;
                    case "onPartialToolCallWithContext":
                        partialToolWithContext = (BiConsumer<PartialToolCall,PartialToolCallContext>) args[0]; return proxy;
                    case "start": {
                        context.progress = this::emitProgress;
                        context.stageOutput = presentation::stage;
                        context.requestStarting = activity::request;
                        ContextMonitor.watch(activity::status);
                        activity.start();
                        delegate.onIntermediateResponse(response -> {
                            if (terminal.get()) return;
                            presentation.intermediate(response.aiMessage() == null ? null : response.aiMessage().text());
                            if (intermediate != null) intermediate.accept(response);
                        });
                        delegate.beforeToolExecution(tool -> {
                            if (terminal.get()) return;
                            activity.toolStarted(tool.request().id(), tool.request().name());
                            if (beforeTool != null) beforeTool.accept(tool);
                        });
                        delegate.onToolExecuted(tool -> {
                            if (terminal.get()) return;
                            activity.toolFinished(tool.request().id(), tool.request().name());
                            presentation.tool(tool);
                            if (afterTool != null) afterTool.accept(tool);
                        });
                        delegate.onPartialToolCallWithContext((part, ctx) -> {
                            if (terminal.get()) return;
                            upstreamHandle = ctx.streamingHandle(); activity.output();
                            if (partialToolWithContext != null) partialToolWithContext.accept(part, ctx);
                            else if (partialTool != null) partialTool.accept(part);
                        });
                        delegate.onCompleteResponse(response -> {
                            AiMessage message = response.aiMessage();
                            if (message == null || (!message.hasToolExecutionRequests() &&
                                (message.text() == null || message.text().isBlank()))) {
                                // A provider can end a queued/incomplete response without any answer.
                                // Do not let that appear as successful completion or leave the UI spinning.
                                if (!terminal.get() && memory != null) {
                                    var retained = new java.util.ArrayList<>(memory.messages());
                                    if (!retained.isEmpty() && retained.get(retained.size()-1) instanceof AiMessage last &&
                                        !last.hasToolExecutionRequests() && (last.text()==null || last.text().isBlank())) {
                                        retained.remove(retained.size()-1); memory.set(retained);
                                    }
                                }
                                failed(new java.io.EOFException("API returned no final answer or tool call"));
                                return;
                            }
                            if (!terminal.compareAndSet(false, true)) return;
                            stopMonitoring();
                            context.archive.store(response.aiMessage());
                            presentation.finish(message.text(), false);
                            if (complete != null) complete.accept(response);
                        });
                        delegate.onError(this::failed);
                        try { delegate.start(); }
                        catch (RuntimeException failure) {
                            if (BoundedContext.isContextFailure(failure) || BoundedContext.isTransportFailure(failure)) failed(failure);
                            else { terminal.set(true); stopMonitoring(); throw failure; }
                        }
                        return null;
                    }
                    default: break;
                }
                Object value = ProgressAssistant.invoke(method, delegate, args);
                return value instanceof TokenStream ? proxy : value;
            }
        }
        return (TokenStream) Proxy.newProxyInstance(TokenStream.class.getClassLoader(),
            new Class<?>[]{TokenStream.class}, new Callbacks());
    }
}
