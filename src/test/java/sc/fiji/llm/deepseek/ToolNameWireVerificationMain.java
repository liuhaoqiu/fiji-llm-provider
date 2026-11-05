/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.*;
import com.google.gson.*;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.openai.*;

/** Real JSON/SSE serialization against a local server; no hosted inference. */
public final class ToolNameWireVerificationMain {
 static final Gson GSON=new Gson();
 static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
 static Map<String,Object> call(int index,String id,String name,boolean stream){
  var c=new LinkedHashMap<String,Object>();if(stream)c.put("index",index);c.put("id",id);c.put("type","function");c.put("function",Map.of("name",name,"arguments","{}"));return c;
 }
 static void run(boolean streaming)throws Exception{
  var requests=new AtomicInteger();var serverFailure=new AtomicReference<Throwable>();var pool=Executors.newCachedThreadPool();
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(pool);
  server.createContext("/v1/chat/completions",ex->{try{
   requests.incrementAndGet();var r=JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
   check(r.has("stream")&&r.get("stream").getAsBoolean()==streaming||!streaming,"transport mode");
   var results=new HashMap<String,JsonObject>();for(var m:r.getAsJsonArray("messages")){var v=m.getAsJsonObject();if(v.get("role").getAsString().equals("tool"))results.put(v.get("tool_call_id").getAsString(),v);}
   String text;List<Map<String,Object>> calls;
   if(!results.containsKey("before")){text="PUBLIC_INITIAL_PROGRESS";calls=List.of(call(0,"before",ToolNameRecoveryVerificationMain.REAL,streaming));}
   else if(!results.containsKey("broken")){check(results.get("before").get("content").getAsString().equals("COMPLETED_ONCE"),"valid result unchanged on wire");text="PUBLIC_BEFORE_INVALID_TOOL";calls=List.of(call(0,"broken",ToolNameRecoveryVerificationMain.BAD,streaming),call(1,"batch",ToolNameRecoveryVerificationMain.REAL,streaming));}
   else{var error=JsonParser.parseString(results.get("broken").get("content").getAsString()).getAsJsonObject();check(error.get("error").getAsString().equals("unknown_tool")&&!error.get("executed").getAsBoolean(),"malformed call produces correlated error on wire");check(results.get("batch").get("content").getAsString().equals("COMPLETED_ONCE"),"mixed valid result retained");text="FINAL_AFTER_CORRECTION";calls=List.of();}
   var message=new LinkedHashMap<String,Object>();message.put("role","assistant");message.put("content",text);if(!calls.isEmpty())message.put("tool_calls",calls);
   var choice=new LinkedHashMap<String,Object>();choice.put("index",0);choice.put(streaming?"delta":"message",message);choice.put("finish_reason",calls.isEmpty()?"stop":"tool_calls");
   String json=GSON.toJson(Map.of("id","local-fixture","model","fixture","object",streaming?"chat.completion.chunk":"chat.completion","created",0,"choices",List.of(choice)));
   byte[] bytes=(streaming?"data: "+json+"\n\ndata: [DONE]\n\n":json).getBytes(StandardCharsets.UTF_8);ex.getResponseHeaders().set("Content-Type",streaming?"text/event-stream":"application/json");ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);
  }catch(Throwable t){serverFailure.set(t);try{ex.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{ex.close();}});server.start();
  try(var f=new ToolNameRecoveryVerificationMain.Fixture(ToolNameRecoveryVerificationMain.BAD,false)){
   String base="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
   f.sync=OpenAiChatModel.builder().baseUrl(base).apiKey("synthetic-local-only").modelName("fixture").maxRetries(0).build();
   f.streaming=OpenAiStreamingChatModel.builder().baseUrl(base).apiKey("synthetic-local-only").modelName("fixture").build();
   var memory=MessageWindowChatMemory.withMaxMessages(30);var a=ToolNameRecoveryVerificationMain.wire(false,"DeepSeek",f,1000).createAssistant(ToolNameRecoveryVerificationMain.Assistant.class,"DeepSeek","fixture",memory,null);
   if(streaming){var end=new CompletableFuture<String>();var count=new AtomicInteger();a.stream("local wire fixture").onPartialResponse(x->{}).onCompleteResponse(r->{count.incrementAndGet();end.complete(r.aiMessage().text());}).onError(end::completeExceptionally).start();check(end.get(15,TimeUnit.SECONDS).equals("FINAL_AFTER_CORRECTION")&&count.get()==1,"SSE reaches one final reply");}
   else check(a.chat("local wire fixture").equals("FINAL_AFTER_CORRECTION"),"JSON reaches final reply");
   check(serverFailure.get()==null,"local server validation: "+serverFailure.get());check(requests.get()==3&&f.executions.get()==2,"no HTTP retry or completed tool replay");f.verify(memory.messages());
  }finally{server.stop(0);pool.shutdownNow();}
 }
 public static void main(String[] args)throws Exception{run(false);run(true);System.out.println("PASS: real Chat Completions JSON/SSE round trips preserve malformed tool correlation, emit not-executed error, retain mixed valid tools and finish without network/tool replay.");}
}
