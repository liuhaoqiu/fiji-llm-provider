/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.*;
import dev.langchain4j.service.tool.*;
import sc.fiji.llm.assistant.DefaultAssistantService;
import sc.fiji.llm.image.ImageRenderingService;
import sc.fiji.llm.provider.*;
import sc.fiji.llm.tools.AiToolService;
import com.google.gson.JsonParser;

/** Actual provider-service loops; malformed names are never guessed or executed. */
public final class ToolNameRecoveryVerificationMain {
 public interface Assistant {TokenStream stream(String task);String chat(String task);}
 static final String REAL="synthetic_probe", BAD="fiji_script_repl</｜｜DSML｜｜ parameter>\n<｜｜DSML｜｜ parameter name=";
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static ToolExecutionRequest call(String id,String name){return ToolExecutionRequest.builder().id(id).name(name).arguments("{}").build();}
 static class Fixture implements AutoCloseable {
  final String bad;final boolean repeat;final AtomicInteger requests=new AtomicInteger(),executions=new AtomicInteger(),handled=new AtomicInteger();
  final Set<String> executed=new HashSet<>();final ExecutorService pool=Executors.newSingleThreadExecutor();
  ChatModel sync;StreamingChatModel streaming;
  Fixture(String name,boolean repeated){bad=name;repeat=repeated;
   sync=new ChatModel(){public ChatResponse doChat(ChatRequest r){return next(r);}};
   streaming=new StreamingChatModel(){public void doChat(ChatRequest r,StreamingChatResponseHandler h){pool.execute(()->{try{
    var response=next(r);var calls=response.aiMessage().toolExecutionRequests();if(response.aiMessage().text()!=null)h.onPartialResponse(response.aiMessage().text());
    if(calls!=null)for(int i=0;i<calls.size();i++)h.onCompleteToolCall(new CompleteToolCall(i,calls.get(i)));h.onCompleteResponse(response);
   }catch(Throwable t){h.onError(t);}});}};
  }
  ChatResponse next(ChatRequest r){int n=requests.incrementAndGet();
   if(repeat)return ChatResponse.builder().aiMessage(AiMessage.from(call("bad-"+n,bad))).build();
   if(n==1)return ChatResponse.builder().aiMessage(AiMessage.from("PUBLIC_INITIAL_PROGRESS",List.of(call("before",REAL)))).build();
   if(n==2)return ChatResponse.builder().aiMessage(AiMessage.from("PUBLIC_BEFORE_INVALID_TOOL",List.of(call("broken",bad),call("batch",REAL)))).build();
   check(n==3,"no request replay");verify(r.messages());return ChatResponse.builder().aiMessage(AiMessage.from("FINAL_AFTER_CORRECTION")).build();
  }
  void verify(List<ChatMessage> messages){
   var t=messages.stream().filter(m->m instanceof ToolExecutionResultMessage x&&"broken".equals(x.id())).map(m->(ToolExecutionResultMessage)m).findFirst().orElseThrow();
   var j=JsonParser.parseString(t.text()).getAsJsonObject();check(j.get("error").getAsString().equals("unknown_tool")&&!j.get("executed").getAsBoolean(),"not-executed error reaches next round");
   check(t.toolName().equals(bad),"correlation name unchanged");
   check(messages.stream().anyMatch(m->m instanceof AiMessage a&&a.toolExecutionRequests()!=null&&a.toolExecutionRequests().stream().anyMatch(c->c.name().equals(bad))),"malformed request not rewritten");
   for(String id:List.of("before","batch"))check(messages.stream().anyMatch(m->m instanceof ToolExecutionResultMessage x&&id.equals(x.id())&&x.text().equals("COMPLETED_ONCE")),"valid result preserved: "+id);
  }
  AiToolService tools(){var spec=ToolSpecification.builder().name(REAL).description("Synthetic explicit edit").parameters(JsonObjectSchema.builder().build()).build();
   ToolExecutor tool=(r,id)->{check(r.name().equals(REAL),"must not guess/normalize unknown names");check(executed.add(r.id()),"must not replay completed actions");executions.incrementAndGet();return "COMPLETED_ONCE";};
   return AssistantExecutionVerificationMain.proxy(AiToolService.class,(p,m,a)->switch(m.getName()){
    case "getToolsWithExecutors"->Map.of(spec,tool);
    case "handleArgumentError","handleExecutionError"->{handled.incrementAndGet();yield ToolErrorHandlerResult.text("original handler");}
    default->null;
   });
  }
  public void close(){pool.shutdownNow();}
 }
 static LLMProvider provider(String name,Fixture f){return switch(name){
  case "DeepSeek"->new DeepSeekProvider(){public ChatModel createChatModel(String m){return f.sync;}public StreamingChatModel createStreamingChatModel(String m){return f.streaming;}};
  case "SiliconFlow"->new SiliconFlowProvider(){public ChatModel createChatModel(String m){return f.sync;}public StreamingChatModel createStreamingChatModel(String m){return f.streaming;}};
  case "Volcengine"->new VolcengineProvider(){public ChatModel createChatModel(String m){return f.sync;}public StreamingChatModel createStreamingChatModel(String m){return f.streaming;}};
  case "NVIDIA"->new NvidiaProvider(){public ChatModel createChatModel(String m){return f.sync;}public StreamingChatModel createStreamingChatModel(String m){return f.streaming;}};
  default->throw new AssertionError(name);
 };}
 static DefaultAssistantService wire(boolean original,String name,Fixture f,int cap)throws Exception{
  var s=original?new DefaultAssistantService():new ProviderAssistantService();var p=provider(name,f);
  var ps=AssistantExecutionVerificationMain.proxy(ProviderService.class,(o,m,a)->m.getName().equals("getProvider")?p:null);var ts=f.tools();var is=AssistantExecutionVerificationMain.proxy(ImageRenderingService.class,(o,m,a)->null);
  AssistantExecutionVerificationMain.set(s,DefaultAssistantService.class,"providerService",ps);AssistantExecutionVerificationMain.set(s,DefaultAssistantService.class,"aiToolService",ts);AssistantExecutionVerificationMain.set(s,DefaultAssistantService.class,"imageRenderingService",is);
  if(s instanceof ProviderAssistantService)for(var e:Map.of("providers",ps,"tools",ts,"images",is,"prefs",AssistantExecutionVerificationMain.preferences(new HashMap<>(Map.of(AssistantExecutionSettings.ROUND_TRIPS_KEY,cap)))).entrySet())AssistantExecutionVerificationMain.set(s,ProviderAssistantService.class,e.getKey(),e.getValue());return s;
 }
 static void flow(String name,String bad,boolean sync,boolean baseline)throws Exception{
  try(var f=new Fixture(bad,false)){
   var memory=MessageWindowChatMemory.withMaxMessages(30);var a=wire(false,name,f,1000).createAssistant(Assistant.class,name,"fixture",memory,null);
   if(sync)check(a.chat("Synthetic task").equals("FINAL_AFTER_CORRECTION"),"sync completes");
   else{
    var body=new StringBuilder();var failure=new AtomicReference<Throwable>();var count=new AtomicInteger();var done=new CountDownLatch(1);
    a.stream("Synthetic task").onPartialResponse(body::append).onCompleteResponse(r->{if(!r.aiMessage().text().equals("FINAL_AFTER_CORRECTION"))failure.set(new AssertionError("final reply changed"));count.incrementAndGet();done.countDown();}).onError(t->{failure.set(t);done.countDown();}).start();check(done.await(15,TimeUnit.SECONDS),"stream ended");
    if(baseline){check(failure.get()!=null&&failure.get().getMessage().contains("no such tool exists"),"old defect reproduced: "+failure.get());check(f.executions.get()==1,"only prior valid action completed");System.out.println("REPRODUCED: unchanged 0.5.18 aborts on actual DSML-contaminated tool name after a completed action.");return;}
    check(failure.get()==null&&count.get()==1,"single successful stream completion: "+failure.get());check(body.toString().contains("PUBLIC_INITIAL_PROGRESS")&&body.toString().contains("PUBLIC_BEFORE_INVALID_TOOL")&&body.toString().contains("Final report")&&body.toString().endsWith("FINAL_AFTER_CORRECTION"),"progress and final output survive");
   }
   check(f.requests.get()==3&&f.executions.get()==2&&f.handled.get()==0,"only valid tools execute once; other handlers unaffected");f.verify(memory.messages());
  }
 }
 static void repeatedAndOriginal()throws Exception{
  try(var f=new Fixture("unknown_tool",true)){
   var a=wire(false,"DeepSeek",f,3).createAssistant(Assistant.class,"DeepSeek","fixture",null,null);
   try{a.chat("fixture");throw new AssertionError("unbounded requests");}catch(RuntimeException e){check(e.getMessage().contains("exceeded 3 tool calling round trips"),"finite existing budget: "+e);}
   check(f.executions.get()==0,"repeated unknown calls execute nothing");
  }
  try(var f=new Fixture(BAD,false)){
   var a=wire(true,"DeepSeek",f,3).createAssistant(Assistant.class,"DeepSeek","fixture",null,null);
   try{a.chat("fixture");throw new AssertionError("original service altered");}catch(RuntimeException e){check(e.getMessage().contains("no such tool exists"),"original behavior retained");}
  }
 }
 public static void main(String[] args)throws Exception{
  System.setProperty("java.awt.headless","true");if(args.length>0){flow("DeepSeek",BAD,false,true);return;}
  for(String n:List.of("DeepSeek","SiliconFlow","Volcengine","NVIDIA")){flow(n,BAD,false,false);flow(n,BAD,true,false);}
  flow("DeepSeek",REAL+"</｜｜DSML｜｜ parameter>",false,false);flow("DeepSeek","totally_missing_tool",false,false);repeatedAndOriginal();
  System.out.println("PASS: four provider sync/stream recovery; exact malformed/unknown names, no guessed execution/replay, feedback/memory correlation, retained progress, single final reply, finite existing budget and unchanged original service.");
 }
}
