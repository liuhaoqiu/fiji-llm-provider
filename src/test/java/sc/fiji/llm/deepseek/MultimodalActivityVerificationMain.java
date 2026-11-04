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
import dev.langchain4j.service.tool.*;

/** Actual AI-service image tool callbacks through the progress/status wrapper. */
public final class MultimodalActivityVerificationMain {
 public interface Assistant{TokenStream stream(String prompt);}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static ImageContent image(){return ImageContent.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5xkAAAAASUVORK5CYII=","image/png");}
 public static void main(String[] args)throws Exception{
  System.setProperty("java.awt.headless","true");
  boolean baseline=args.length>0&&args[0].equals("baseline");
  if(baseline){flow("fiji_image_view",List.of(TextContent.from("image metadata"),image()),true);return;}
  flow("fiji_image_view",List.of(TextContent.from("image metadata"),image()),false);
  flow("fiji_image_view",List.of(image()),false);
  flow("fiji_command_inputs_status",List.of(TextContent.from("{\"run_id\":\"fixture\",\"status\":\"success\"}"),image()),false);
  flow("fiji_command_inputs_status",List.of(TextContent.from("{\"run_id\":\"fixture\","),TextContent.from("\"status\":\"success\"}")),false);
  flow("fiji_macro_run",List.of(TextContent.from("{\"run_id\":\"fixture\",\"status\":\"success\"}")),false);
  System.out.println("PASS: real multimodal tool callbacks, unchanged image/text content and memory, later model round/final output, multipart status and single-text compatibility");
 }
 static void flow(String name,List<Content> contents,boolean baseline)throws Exception{
  var request=ToolExecutionRequest.builder().id("image-tool").name(name).arguments("{}").build();
  var toolResult=ToolExecutionResult.builder().resultContents(contents).build();
  var rounds=new AtomicInteger();var seen=new AtomicReference<ToolExecution>();var failure=new AtomicReference<Throwable>();var completed=new AtomicInteger();var body=new StringBuilder();var done=new CountDownLatch(1);
  var executor=Executors.newSingleThreadExecutor();var context=new BoundedContext(24000,1_048_576);var memory=MessageWindowChatMemory.withMaxMessages(20);
  StreamingChatModel model=new StreamingChatModel(){@Override public void doChat(ChatRequest req,StreamingChatResponseHandler handler){executor.execute(()->{
   try{
    if(rounds.incrementAndGet()==1){handler.onCompleteToolCall(new CompleteToolCall(0,request));handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(request)).build());}
    else{
     var result=req.messages().stream().filter(m->m instanceof ToolExecutionResultMessage).map(m->(ToolExecutionResultMessage)m).findFirst().orElseThrow();
     check(result.contents().equals(contents),"tool content reaches next model round unchanged");
     handler.onPartialResponse("FINAL_AFTER_IMAGE");handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("FINAL_AFTER_IMAGE")).build());
    }
   }catch(Throwable error){handler.onError(error);}
  });}};
  var spec=ToolSpecification.builder().name(name).description("Synthetic multipart output").parameters(JsonObjectSchema.builder().build()).build();
  ToolExecutor tool=new ToolExecutor(){public String execute(ToolExecutionRequest r,Object id){throw new AssertionError("must use structured result");}public ToolExecutionResult executeWithContext(ToolExecutionRequest r,dev.langchain4j.invocation.InvocationContext invocation){return toolResult;}};
  try{
   var raw=AiServices.builder(Assistant.class).streamingChatModel(model).tools(Map.of(spec,tool)).chatMemory(memory).build();
   var wrapped=ProgressAssistant.wrap(Assistant.class,raw,context,memory);
   wrapped.stream("Inspect synthetic image").onPartialResponse(body::append).onToolExecuted(seen::set).onError(e->{failure.set(e);done.countDown();}).onCompleteResponse(r->{completed.incrementAndGet();done.countDown();}).start();
   check(done.await(10,TimeUnit.SECONDS),"tool flow terminated");
   if(baseline){check(failure.get()!=null&&failure.get().toString().contains("resultText() cannot be called"),"old status callback must reproduce exact failure: "+failure.get());System.out.println("REPRODUCED: 0.5.13 image-tool status callback: "+failure.get());return;}
   check(failure.get()==null,"multipart callback must not fail: "+failure.get());
   check(completed.get()==1&&rounds.get()==2&&body.toString().contains("FINAL_AFTER_IMAGE"),"stream continues to one final output");
   check(seen.get()!=null&&seen.get().resultContents().equals(contents),"downstream UI callback retains original content");
   check(memory.messages().stream().anyMatch(m->m instanceof ToolExecutionResultMessage t&&t.contents().equals(contents)),"memory retains image and all text elements");
  }finally{executor.shutdownNow();}
 }
}
