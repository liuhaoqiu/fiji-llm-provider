/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import com.google.gson.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecution;
import org.scijava.ui.swing.script.*;
import sc.fiji.llm.script.*;
import sc.fiji.llm.ui.TextEditorUtils;

/** Observed engineering failures only; no hosted inference or user images. */
public final class EngineeringVerificationMain {
    static{net.imagej.patcher.LegacyInjector.preinit();}
    static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        try{
            nestedStatuses();waiting();
            if(args.length>0)editTargets();
            System.out.println("PASS: nested script failures/success/warnings and deduplication; bounded completion/error/timeout/cancellation waits without replay or EDT blocking");
            Runtime.getRuntime().halt(0);
        }catch(Throwable failure){failure.printStackTrace();Runtime.getRuntime().halt(1);}
    }
    static ToolExecution execution(String result){
        var now=java.time.LocalDateTime.now();
        var context=dev.langchain4j.invocation.InvocationContext.builder().invocationId(UUID.randomUUID()).interfaceName("EngineeringFixture")
            .methodName("run").methodArguments(List.of("fixture")).chatMemoryId("fixture").timestampNow().build();
        return ToolExecution.builder().request(ToolExecutionRequest.builder().id("fixture").name("fiji_script_run").arguments("{}").build())
            .result(result).startTime(now).finishTime(now).invocationContext(context).build();
    }
    static void nestedStatuses(){
        var body=new StringBuilder();var p=new StagePresentation(body::append);
        String failure="{\"ran_script\":{\"run_id\":\"cleanup\",\"script_name\":\"cleanup.groovy\",\"status\":\"finished_with_errors\",\"errors\":\"ArrayIndexOutOfBoundsException\"}}";
        p.tool(execution(failure));p.tool(execution(failure));
        check(body.toString().contains("Execution reported an error")&&body.toString().contains("cleanup.groovy"),"nested error must reach public stage output with target name");
        check(body.indexOf("Execution reported an error")==body.lastIndexOf("Execution reported an error"),"duplicate status not repeated");
        p.tool(execution("{\"ran_script\":{\"run_id\":\"cleanup\",\"status\":\"success\"}}"));
        p.tool(execution("{\"ran_script\":{\"run_id\":\"other\",\"status\":\"finished_with_warnings\"}}"));
        check(body.toString().contains("Execution finished: success")&&body.toString().contains("finished with warnings"),"nested terminal statuses distinct");
        var evidence=ScriptValidation.evidence("0:1","fixture");evidence.addProperty("script_name","delivered.ijm");p.validation(evidence);p.finish("done",false);
        check(body.toString().contains("delivered.ijm")&&body.toString().contains("0:1"),"execution badge names its script");
    }
    static String pending(FutureTask<JsonObject> future){
        String id="engineering-"+UUID.randomUUID();WorkflowTools.jobs.put(id,new WorkflowTools.Job(System.currentTimeMillis(),Set.of(),future));return id;
    }
    static void waiting()throws Exception{
        var calls=new AtomicInteger();var finish=new CountDownLatch(1);
        var f=new FutureTask<JsonObject>(()->{calls.incrementAndGet();finish.await();return JsonParser.parseString("{\"status\":\"success\"}").getAsJsonObject();});
        String id=pending(f);new Thread(f).start();
        try{
            long start=System.nanoTime();check(WorkflowTools.waitStatus(id,0,()->false).get("status").getAsString().equals("running"),"explicit immediate poll remains available");
            check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1),"zero wait is immediate");
            UiAutomationTools.edt(()->{check(WorkflowTools.waitStatus(id,20,()->false).get("status").getAsString().equals("running"),"never block EDT");return null;});
            try{WorkflowTools.waitStatus(id,31,()->false);throw new AssertionError("invalid wait accepted");}catch(IllegalArgumentException expected){}
            try{WorkflowTools.waitStatus(id,20,()->true);throw new AssertionError("cancellation ignored");}catch(CancellationException expected){}
            check(!f.isCancelled()&&!f.isDone(),"cancel wait does not cancel command work");
            var pool=Executors.newSingleThreadExecutor();
            try{
                var result=pool.submit(()->WorkflowTools.waitStatus(id,20,()->false));
                Thread.sleep(100);check(!result.isDone(),"wait actually awaits pending task");
                finish.countDown();check(result.get(3,TimeUnit.SECONDS).get("status").getAsString().equals("success"),"wait returns early on completion");
            }finally{pool.shutdownNow();}
            check(calls.get()==1,"no task replay during polling");
            var err=new FutureTask<JsonObject>(()->{throw new IllegalStateException("fixture failure");});String errId=pending(err);err.run();
            check(WorkflowTools.waitStatus(errId,20,()->false).get("status").getAsString().equals("error"),"failed task ends wait");WorkflowTools.jobs.remove(errId);
            var slow=new FutureTask<JsonObject>(JsonObject::new);String slowId=pending(slow);long then=System.nanoTime();
            check(WorkflowTools.waitStatus(slowId,1,()->false).get("status").getAsString().equals("running"),"bounded timeout returns running");
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-then);check(elapsed>=900&&elapsed<2500,"wait timeout bounded");WorkflowTools.jobs.remove(slowId);
        }finally{finish.countDown();WorkflowTools.jobs.remove(id);}
    }
    static void editTargets()throws Exception{
        // This isolated JVM exits after assertions; legacy context disposal would prompt to save synthetic tabs.
        var context=MacroExecutionVerificationMain.fixtureContext();{
            var editor=UiAutomationTools.edt(()->{var e=new TextEditor(context);e.newTab("DELIVERED_MACRO","ImageJ Macro");e.getEditorPane().setFileName("delivered.ijm");e.setSize(400,250);e.setLocation(-20000,-20000);e.setVisible(true);TextEditorUtils.recordLastFocusedEditor(e);return e;});
            try{
                var pane=UiAutomationTools.edt(editor::getEditorPane);
                String id=UiAutomationTools.edt(()->new ScriptID(TextEditor.instances.indexOf(editor),TextEditorUtils.getTabIndex(editor,editor.getTab())).toString());
                var target=ScriptTargets.read(id);String handle=target.get("script_handle").getAsString(),hash=target.get("current_sha256").getAsString();
                var cleanup=UiAutomationTools.edt(()->{editor.newTab("CLEANUP","Groovy");editor.getEditorPane().setFileName("cleanup.groovy");return editor.getEditorPane();});
                UiAutomationTools.edt(()->{new ScriptEditorToolPlugin().replaceScript("WRONG_TAB");return null;});
                check(UiAutomationTools.edt(()->pane.getText().equals("DELIVERED_MACRO")&&cleanup.getText().equals("WRONG_TAB")),"original active-tab replacement reproduces target confusion");
                var changed=ScriptTargets.replace(handle,hash,"VERIFIED_MACRO");
                check(changed.getAsJsonObject("script_execution_evidence").get("current_version_executed_successfully").getAsBoolean()==false,"edit remains unexecuted");
                check(UiAutomationTools.edt(()->pane.getText().equals("VERIFIED_MACRO")&&cleanup.getText().equals("WRONG_TAB")&&editor.getEditorPane()==cleanup),"target edit ignores active tab and does not switch focus");
                try{ScriptTargets.replace(handle,hash,"STALE_EDIT");throw new AssertionError("stale hash accepted");}catch(IllegalStateException expected){}
                check(UiAutomationTools.edt(()->pane.getText().equals("VERIFIED_MACRO")),"stale edit leaves bytes unchanged");
                var latest=ScriptTargets.read(id);UiAutomationTools.edt(()->{pane.setText("HUMAN_EDIT");return null;});
                try{ScriptTargets.replace(handle,latest.get("current_sha256").getAsString(),"LOST_UPDATE");throw new AssertionError("concurrent edit overwritten");}catch(IllegalStateException expected){}
                UiAutomationTools.edt(()->{editor.dispose();return null;});
                try{ScriptTargets.replace(handle,ScriptValidation.hash("HUMAN_EDIT"),"AFTER_CLOSE");throw new AssertionError("closed editor accepted");}catch(IllegalStateException expected){}
                System.out.println("PASS: real two-tab active-edit defect reproduced; stable explicit target, atomic hash conflict rejection, no focus switch and closed-target rejection");
            }finally{UiAutomationTools.edt(()->{editor.dispose();return null;});}
        }
    }
}
