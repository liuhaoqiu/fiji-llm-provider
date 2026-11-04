/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.*;

/** Fault injection at the public display sink inside actual AI-service callbacks. */
public final class DisplayIsolationVerificationMain {
 public interface Assistant {TokenStream stream(String task);}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args)throws Exception{
  boolean baseline=args.length>0;for(String fault:List.of("runtime","linkage","assertion")){flow(fault,baseline);if(baseline)return;}
  System.out.println("PASS: injected display RuntimeException, LinkageError and AssertionError cannot prevent downstream tool delivery, preserved results/memory, next model round and single final response");
 }
 static void flow(String fault,boolean baseline)throws Exception{
  var call=ToolExecutionRequest.builder().id("fixture").name("fiji_macro_run").arguments("{}").build();
  String value="{\"run_id\":\"fixture\",\"status\":\"running\"}";
  var rounds=new AtomicInteger();var received=new AtomicInteger();var injected=new AtomicBoolean();var completed=new AtomicInteger();var body=new StringBuilder();var failure=new AtomicReference<Throwable>();var done=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
  var memory=MessageWindowChatMemory.withMaxMessages(20);var context=new BoundedContext(24000,1_048_576);
  StreamingChatModel model=new StreamingChatModel(){@Override public void doChat(ChatRequest request,StreamingChatResponseHandler handler){pool.execute(()->{
   try{
    if(rounds.incrementAndGet()==1){handler.onCompleteToolCall(new CompleteToolCall(0,call));handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(call)).build());}
    else{check(request.messages().stream().anyMatch(m->m instanceof ToolExecutionResultMessage t&&t.text().equals(value)),"original result reaches model unchanged");handler.onPartialResponse("FINAL_SURVIVED");handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("FINAL_SURVIVED")).build());}
   }catch(Throwable t){handler.onError(t);}
  });}};
  var spec=ToolSpecification.builder().name("fiji_macro_run").description("Synthetic run status").parameters(JsonObjectSchema.builder().build()).build();
  try{
   var raw=AiServices.builder(Assistant.class).streamingChatModel(model).tools(Map.of(spec,(dev.langchain4j.service.tool.ToolExecutor)(r,id)->value)).chatMemory(memory).build();
   var wrapper=ProgressAssistant.wrap(Assistant.class,raw,context,memory);
   wrapper.stream("fixture").onPartialResponse(text->{
    if(text.contains("Progress")&&injected.compareAndSet(false,true))switch(fault){case "runtime"->throw new IllegalStateException("injected display failure");case "linkage"->throw new NoSuchMethodError("injected optional display incompatibility");default->throw new AssertionError("injected display assertion");}
    body.append(text);
   }).onToolExecuted(t->{check(t.result().equals(value),"downstream tool callback receives original result");received.incrementAndGet();})
   .onCompleteResponse(r->{completed.incrementAndGet();done.countDown();}).onError(t->{failure.set(t);done.countDown();}).start();
   check(done.await(10,TimeUnit.SECONDS),"flow ends");check(injected.get(),"display failure was actually injected");
   if(baseline){check(failure.get()!=null&&received.get()==0,"baseline display failure aborts original result delivery");System.out.println("REPRODUCED: optional display failure aborts 0.5.14 tool callback: "+failure.get());return;}
   check(failure.get()==null&&received.get()==1&&rounds.get()==2&&completed.get()==1&&body.toString().contains("FINAL_SURVIVED"),"display failure must not abort execution: "+failure.get());
   check(memory.messages().stream().anyMatch(m->m instanceof ToolExecutionResultMessage t&&t.text().equals(value)),"original result stays in memory");
  }finally{pool.shutdownNow();}
 }
}
