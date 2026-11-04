/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import org.scijava.ui.swing.script.*;
import sc.fiji.llm.script.*;
import sc.fiji.llm.ui.TextEditorUtils;
import com.google.gson.*;

/** Isolated synthetic reproductions of the observed 0.5.12 status/UI defects. */
public class StatusVerificationMain {
 static {net.imagej.patcher.LegacyInjector.preinit();}
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  try{if(args.length>0)gui(args[0]);else{headless();}System.out.println("PASS: truthful execution diagnostics, script evidence and background activity");Runtime.getRuntime().halt(0);}
  catch(Throwable t){t.printStackTrace();Runtime.getRuntime().halt(1);}
 }
 static void headless()throws Exception{
  var evidence=ScriptValidation.evidence("0:0","println 'done'",false);
  check(evidence.get("status").getAsString().equals("not_monitored")&&!evidence.get("execution_evidence_supported").getAsBoolean(),"ordinary scripts are unknown to exact-byte checker, not unexecuted");
  var output=new StringBuilder();var presentation=new StagePresentation(output::append);presentation.validation(evidence);presentation.finish("DONE",false);
  check(output.toString().contains("does not cover")&&!output.toString().contains("no matching successful execution"),"final badge accurately states checker coverage");
  var stages=new StringBuilder();var p=new StagePresentation(stages::append);
  p.tool(EngineeringVerificationMain.execution("{\"ran_script\":{\"status\":\"busy\",\"started\":false,\"blocking_script_name\":\"background.groovy\",\"blocking_run_id\":\"pending-1\"}}"));
  p.tool(EngineeringVerificationMain.execution("{\"ran_script\":{\"run_id\":\"bad\",\"status\":\"infrastructure_error\",\"diagnostic\":\"outer: caused by actual startup cause\"}}"));
  check(stages.toString().contains("Not started")&&stages.toString().contains("background.groovy")&&stages.toString().contains("pending-1")&&stages.toString().contains("actual startup cause"),"busy identity and real reason appear publicly");
  String causal=MacroExecutionService.describeFailure(new IllegalStateException("outer",new IllegalArgumentException("actual cause")));
  check(causal.contains("outer")&&causal.contains("actual cause"),"startup diagnostic retains root cause");
  var now=new java.util.concurrent.atomic.AtomicLong();var notices=new ArrayList<String>();
  try(var a=new StreamActivity(notices::add,now::get,java.time.Duration.ofSeconds(60))){
   a.start();a.request();check(a.status().contains("Waiting for API"),"metadata-only API activity retained");
   a.toolStarted("fixture","fixture_tool");check(a.status().contains("Tool running"),"metadata-only tool activity retained");
   a.toolFinished("fixture","fixture_tool");check(a.status().contains("Waiting for API"),"tool result contents not needed for activity state");
   a.close();now.set(java.time.Duration.ofSeconds(70).toNanos());a.tick();check(notices.isEmpty()&&a.status().contains("Stopped"),"no stale waiting notices after completion");
  }
 }
 static void gui(String mode)throws Exception{
  var context=MacroExecutionVerificationMain.fixtureContext();
  var service=context.service(ScriptExecutionService.class);
  var created=ScriptTargets.create(context,"slow_fixture.groovy","Groovy","System.setProperty('fiji.fixture.started','yes'); println 'BEGIN'; Thread.sleep(2500); println 'END';");
  var first=TextEditor.instances.get(Integer.parseInt(created.get("script_id").getAsString().split(":")[0]));
  ScriptID id=new ScriptID(TextEditor.instances.indexOf(first),UiAutomationTools.edt(()->TextEditorUtils.getTabIndex(first,first.getTab())));
  var pool=Executors.newSingleThreadExecutor();
  try{
   var running=pool.submit(()->service.run(id,ScriptExecutionService.RunKind.SCRIPT,true).toJson());
   long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
   while(!"yes".equals(System.getProperty("fiji.fixture.started"))&&System.nanoTime()<deadline)Thread.sleep(30);
   if(!"yes".equals(System.getProperty("fiji.fixture.started")))System.out.println("EARLY_RESULT "+running.get(40,TimeUnit.SECONDS));
   check("yes".equals(System.getProperty("fiji.fixture.started")),"real Groovy body entered");
   var second=ScriptTargets.create(context,"other.groovy","Groovy","println 'OTHER';");
   JsonObject busy=null;
   if(!mode.equals("baseline"))busy=service.run(id,ScriptExecutionService.RunKind.MACRO,true).toJson();
   var result=running.get(12,TimeUnit.SECONDS);
   if(mode.equals("baseline")){
    check(result.toString().contains("Writer is null"),"baseline must reproduce actual Writer is null");
    System.out.println("REPRODUCED: new-tab creation during real Groovy execution causes "+result);
    return;
   }
   check(!second.get("script_id").getAsString().split(":")[0].equals(created.get("script_id").getAsString().split(":")[0]),"new script uses another editor while first is busy");
   check(result.get("status").getAsString().equals("success")&&result.get("output").getAsString().contains("END"),"original Groovy output and success survive creation");
   check(busy.get("status").getAsString().equals("busy")&&!busy.get("started").getAsBoolean()&&busy.get("blocking_script_name").getAsString().equals("slow_fixture.groovy"),"busy response identifies active script and does not start another run");
   check(ScriptValidation.evidence(id.toString(),"fixture",false).get("status").getAsString().equals("not_monitored"),"successful delegated script never misreported as never run");
   System.out.println("GROOVY_RESULT "+result);
   var failure=ScriptTargets.create(context,"failure_fixture.groovy","Groovy","println 'BEFORE_ERROR'; throw new IllegalArgumentException('REAL_SCRIPT_FAILURE');");
   String[] f=failure.get("script_id").getAsString().split(":");var failed=service.run(new ScriptID(Integer.parseInt(f[0]),Integer.parseInt(f[1])),ScriptExecutionService.RunKind.SCRIPT,true).toJson();
   check(failed.get("status").getAsString().equals("finished_with_errors")&&failed.toString().contains("REAL_SCRIPT_FAILURE")&&failed.toString().contains("BEFORE_ERROR"),"real delegated errors and partial output retained");
   var macro=ScriptTargets.create(context,"after_script.ijm","ImageJ Macro","print(\"AFTER_SCRIPT\");");String[] mi=macro.get("script_id").getAsString().split(":");
   check(service.run(new ScriptID(Integer.parseInt(mi[0]),Integer.parseInt(mi[1])),ScriptExecutionService.RunKind.MACRO,true).toJson().get("status").getAsString().equals("success"),"execution slot released after delegated script failure");
   var invalid=service.run(new ScriptID(9999,0),ScriptExecutionService.RunKind.MACRO,true).toJson();
   check(invalid.get("diagnostic").getAsString().contains("No visible Script Editor found"),"wrapped startup failure exposes original cause");
  }finally{pool.shutdownNow();UiAutomationTools.edt(()->{for(var e:new ArrayList<>(TextEditor.instances))e.dispose();return null;});}
 }
}
