/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import javax.swing.SwingUtilities;
import com.google.gson.Gson;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.*;
import sc.fiji.llm.ui.ChatMessagePanel;
import sc.fiji.llm.chat.*;

/** Real AI-service tool rounds and the installed chat panel; no hosted model. */
public final class StagePresentationVerificationMain {
    public interface Assistant {TokenStream stream(String task);}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static ToolExecutionRequest call(String id,String name,String arguments){return ToolExecutionRequest.builder().id(id).name(name).arguments(arguments).build();}
    public static void main(String[] args)throws Exception {
        System.setProperty("java.awt.headless","true");
        flow();toolIsolation();failedEdit();cancelPulse();
        System.out.println("PASS: live milestone body, real progress-tool/AI-service rounds, folded raw details, separate final report, polling deduplication, serialized history, per-chat isolation and cancellation handle");
    }
    static void flow()throws Exception {
        var context=new BoundedContext(24000,1_048_576);
        var memory=MessageWindowChatMemory.withMaxMessages(30);
        var panel=new ChatMessagePanel(ChatMessagePanel.MessageType.ASSISTANT,"");
        SwingUtilities.invokeAndWait(panel::startWorking);
        var rounds=new AtomicInteger();var polls=new AtomicInteger();
        var complete=new AtomicReference<ChatResponse>();var failure=new AtomicReference<Throwable>();var done=new CountDownLatch(1);
        var intermediates=new AtomicInteger();var executor=Executors.newSingleThreadExecutor();
        var tools=new LinkedHashMap<ToolSpecification,ToolExecutor>();
        for(String name:List.of("fiji_macro_run","fiji_macro_run_status"))tools.put(ToolSpecification.builder().name(name)
            .description("Synthetic execution status").parameters(JsonObjectSchema.builder().build()).build(),(request,id)->
                "{\"run_id\":\"fixture-run\",\"status\":\""+(request.name().endsWith("_status")&&polls.incrementAndGet()>1?"success":"running")+"\",\"raw_detail\":\"RAW_TOOL_MARKER\"}");
        StreamingChatModel model=new StreamingChatModel(){
            @Override public void doChat(ChatRequest request,StreamingChatResponseHandler handler){executor.execute(()->{
                try {
                    int n=rounds.incrementAndGet();
                    check(request.messages().stream().filter(m->m instanceof SystemMessage s&&s.text().contains(StageReporting.MARKER)).count()==1,"presentation instruction appears once");
                    if(n==2)SwingUtilities.invokeAndWait(()->check(panel.getText().contains("REPORT_BEFORE_RUN")&&!panel.getText().contains("最终报告 / Final report"),"progress must already be visible before later model/tool rounds"));
                    handler.onPartialThinking(new PartialThinking("PRIVATE_THINKING_MARKER_"+n));
                    String text=n==1?"PUBLIC_STAGE_NOTE":n==4?"FINAL_BODY":"";
                    if(!text.isEmpty())handler.onPartialResponse(text);
                    List<ToolExecutionRequest> calls=switch(n){
                        case 1->List.of(call("p1",StageReporting.NAME,"{\"message\":\"REPORT_BEFORE_RUN\"}"));
                        case 2->List.of(call("run","fiji_macro_run","{}"),call("poll1","fiji_macro_run_status","{}"));
                        case 3->List.of(call("poll2","fiji_macro_run_status","{}"),call("p2",StageReporting.NAME,"{\"message\":\"REPORT_AFTER_RUN\"}"));
                        default->List.of();
                    };
                    for(int i=0;i<calls.size();i++)handler.onCompleteToolCall(new CompleteToolCall(i,calls.get(i)));
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(calls.isEmpty()?AiMessage.from(text):AiMessage.from(text,calls)).build());
                }catch(Throwable t){handler.onError(t);}
            });}
        };
        try {
            var raw=AiServices.builder(Assistant.class).streamingChatModel(model).tools(StageReporting.tools(tools,context))
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.failInvocation())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.sendExceptionMessageToLlm())
                .chatMemory(memory).chatRequestTransformer(StageReporting::prepare).build();
            var assistant=ProgressAssistant.wrap(Assistant.class,raw,context,memory);
            assistant.stream("Run the synthetic macro").onPartialResponseWithContext((part,ctx)->panel.appendText(part.text()))
                .onPartialThinkingWithContext((part,ctx)->panel.appendThinking(part.text()))
                .beforeToolExecution(t->panel.toolStarted(t.request().name(),t.request().arguments()))
                .onToolExecuted(t->panel.toolFinished(t.request().name(),t.hasFailed(),t.duration().toMillis(),t.result()))
                .onIntermediateResponse(r->intermediates.incrementAndGet())
                .onCompleteResponse(r->{complete.set(r);panel.finishWorking();done.countDown();})
                .onError(t->{failure.set(t);done.countDown();}).start();
            check(done.await(10,TimeUnit.SECONDS),"real AI-service flow must finish");
            check(failure.get()==null,"flow failed: "+failure.get());
            SwingUtilities.invokeAndWait(()->{});
            String body=panel.getText();
            check(rounds.get()==4&&intermediates.get()==3,"all real intermediate rounds retained");
            check(body.contains("PUBLIC_STAGE_NOTE")&&body.contains("REPORT_BEFORE_RUN")&&body.contains("REPORT_AFTER_RUN"),"public intermediate content and report tool remain in the body");
            check(!body.contains("PRIVATE_THINKING_MARKER")&&!body.contains("RAW_TOOL_MARKER"),"raw thinking/tool details stay out of the body");
            check(body.indexOf("Progress")<body.indexOf("Final report")&&body.endsWith("FINAL_BODY"),"final report is a separate last section");
            check(body.indexOf("Execution running")==body.lastIndexOf("Execution running"),"repeated running polls do not spam the body");
            check(panel.getActivity().getSteps().size()>=5,"folded activity remains available");
            check(complete.get().aiMessage().text().equals("FINAL_BODY"),"presentation does not rewrite the model's final answer or memory");
            var message=new SerializedConversation.SerializedConversationMessage();
            message.setDisplayMessage(body);message.setMemoryMessage(ChatMessageConverter.toSerialized(complete.get().aiMessage()));message.setActivity(panel.getActivity());
            Gson gson=new Gson();var replay=gson.fromJson(gson.toJson(message),SerializedConversation.SerializedConversationMessage.class);
            check(replay.getDisplayMessage().equals(body)&&!replay.getActivity().isEmpty(),"display transcript and activity survive the Fiji history schema");
            var restored=new ChatMessagePanel(ChatMessagePanel.MessageType.ASSISTANT,replay.getDisplayMessage());
            restored.showActivity(replay.getActivity());SwingUtilities.invokeAndWait(()->{});
            check(restored.getText().contains("REPORT_BEFORE_RUN")&&restored.getText().contains("Final report"),"reopened display retains both body sections");
            String preview=System.getProperty("fiji.llm.stagePreview");
            if(preview!=null)SwingUtilities.invokeAndWait(()->{
                panel.setSize(1000,1000);layout(panel);
                var image=new java.awt.image.BufferedImage(1000,1000,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics=image.createGraphics();graphics.setColor(java.awt.Color.WHITE);graphics.fillRect(0,0,1000,1000);panel.printAll(graphics);graphics.dispose();
                try{javax.imageio.ImageIO.write(image,"png",new java.io.File(preview));}catch(java.io.IOException e){throw new RuntimeException(e);}
            });
        }finally{executor.shutdownNow();SwingUtilities.invokeAndWait(panel::finishWorking);}
    }
    static void layout(java.awt.Container container){container.doLayout();for(var child:container.getComponents())if(child instanceof java.awt.Container nested)layout(nested);}
    static void failedEdit(){
        var body=new StringBuilder();var display=new StagePresentation(body::append);var now=java.time.LocalDateTime.now();
        var invocation=dev.langchain4j.invocation.InvocationContext.builder().invocationId(UUID.randomUUID())
            .interfaceName(Assistant.class.getName()).methodName("stream").methodArguments(List.of("fixture")).chatMemoryId("fixture").timestampNow().build();
        display.tool(ToolExecution.builder().request(call("failed","fiji_script_replace_content","{}"))
            .result("{\"error\":\"No script available\"}").startTime(now).finishTime(now).invocationContext(invocation).build());
        check(body.toString().contains("Tool reported an error")&&!body.toString().contains("Script updated"),"failed edit must not be presented as a completed edit");
    }
    static void toolIsolation(){
        var a=new BoundedContext(24000,1_048_576);var b=new BoundedContext(24000,1_048_576);
        var left=new StringBuilder();var right=new StringBuilder();a.stageOutput=left::append;b.stageOutput=right::append;
        var toolA=StageReporting.tools(Map.of(),a).values().iterator().next();var toolB=StageReporting.tools(Map.of(),b).values().iterator().next();
        toolA.execute(call("a",StageReporting.NAME,"{\"message\":\"ONLY_A\"}"),null);
        toolB.execute(call("b",StageReporting.NAME,"{\"message\":\"ONLY_B\"}"),null);
        check(left.toString().equals("ONLY_A")&&right.toString().equals("ONLY_B"),"reporting is per assistant/chat");
        try{toolA.execute(call("bad",StageReporting.NAME,"{\"message\":\" \"}"),null);throw new AssertionError("empty report accepted");}catch(IllegalArgumentException expected){}
        var request=ChatRequest.builder().messages(List.of(SystemMessage.from("Keep original system text"),UserMessage.from("user task"))).build();
        var once=StageReporting.prepare(request);check(StageReporting.prepare(once)==once,"presentation prompt is not repeatedly appended");
        check(((SystemMessage)once.messages().get(0)).text().startsWith("Keep original system text"),"original system instruction retained");
    }
    @SuppressWarnings("unchecked") static void cancelPulse(){
        var callbacks=new HashMap<String,Object>();var cancelled=new AtomicBoolean();var context=new BoundedContext(24000,1_048_576);
        TokenStream source=(TokenStream)java.lang.reflect.Proxy.newProxyInstance(TokenStream.class.getClassLoader(),new Class<?>[]{TokenStream.class},(p,m,a)->{
            if(!m.getName().equals("start")){if(a!=null&&a.length>0)callbacks.put(m.getName(),a[0]);return p;}
            var handle=new StreamingHandle(){public void cancel(){cancelled.set(true);}public boolean isCancelled(){return cancelled.get();}};
            ((BiConsumer<PartialResponse,PartialResponseContext>)callbacks.get("onPartialResponseWithContext"))
                .accept(new PartialResponse("BUFFERED_FINAL"),new PartialResponseContext(handle));return null;
        });
        var wrapped=ProgressAssistant.wrap(Assistant.class,p->source,context,null);
        wrapped.stream("start").onPartialThinkingWithContext((part,ctx)->{throw new AssertionError("body draft was mislabeled as reasoning");})
            .onPartialResponseWithContext((part,ctx)->{check(part.text().isEmpty(),"display-control event must not expose pending text");ctx.streamingHandle().cancel();}).onError(e->{}).start();
        check(cancelled.get()&&context.cancelled,"buffering must preserve the upstream cancellation handle");
    }
}
