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
            nestedStatuses();savedScriptBadges();dialogStatuses();literalReasons();waiting();
            if(args.length>0)editTargets();
            System.out.println("PASS: nested script failures/success/warnings and deduplication; bounded completion/error/timeout/cancellation waits without replay or EDT blocking");
            Runtime.getRuntime().halt(0);
        }catch(Throwable failure){failure.printStackTrace();Runtime.getRuntime().halt(1);}
    }
    static ToolExecution execution(String result){
        return execution("fiji_script_run",result);
    }
    static ToolExecution execution(String name,String result){
        var now=java.time.LocalDateTime.now();
        var context=dev.langchain4j.invocation.InvocationContext.builder().invocationId(UUID.randomUUID()).interfaceName("EngineeringFixture")
            .methodName("run").methodArguments(List.of("fixture")).chatMemoryId("fixture").timestampNow().build();
        return ToolExecution.builder().request(ToolExecutionRequest.builder().id("fixture").name(name).arguments("{}").build())
            .result(result).startTime(now).finishTime(now).invocationContext(context).build();
    }
    static JsonObject badgeEvidence(String id,String name,boolean macro,boolean executed){
        var e=ScriptValidation.evidence(id,"fixture",macro);e.addProperty("script_name",name);
        if(macro){e.addProperty("current_version_executed_successfully",executed);e.addProperty("status",executed?"executed_current_version":"modified_since_last_run");}
        return e;
    }
    static String badge(JsonObject saved,JsonObject later,boolean paused){
        var out=new StringBuilder();var display=new StagePresentation(out::append);
        if(saved!=null)display.tool(execution("fiji_analysis_save_bundle",saved.toString()));
        if(later!=null){var receipt=new JsonObject();receipt.add("script_execution_evidence",later);display.tool(execution("fiji_script_create",receipt.toString()));}
        display.finish("done",paused);return out.substring(out.lastIndexOf("## "));
    }
    static JsonObject savedEvidence(JsonObject e){var saved=new JsonObject();saved.addProperty("status","saved");saved.addProperty("bundle_directory","fixture/export");saved.add("script_execution_evidence",e);return saved;}
    static void savedScriptBadges(){
        var delivered=badgeEvidence("0:26","cellpose_count_final.groovy",false,false);
        var cleanup=badgeEvidence("0:27","cleanup_images.groovy",false,false);
        String shown=badge(savedEvidence(delivered),cleanup,false);
        check(shown.contains("Last saved script snapshot evidence")&&shown.contains("cellpose_count_final.groovy")&&shown.contains("0:26")&&!shown.contains("cleanup_images.groovy"),"exported script identity must survive cleanup creation");
        check(shown.contains("does not cover this script language")&&!shown.contains("bytes executed"),"ordinary script remains not monitored");
        var macro=badgeEvidence("0:1","delivered.ijm",true,true);
        var edited=badgeEvidence("0:1","delivered.ijm",true,false);
        shown=badge(savedEvidence(macro),edited,false);
        check(shown.contains("Saved snapshot bytes executed")&&!shown.contains("Current bytes executed"),"later edit must not turn saved evidence into a current-editor claim");
        check(badge(savedEvidence(edited),cleanup,false).contains("Saved snapshot bytes have no matching successful execution"),"unexecuted export must not become verified");
        check(badge(null,cleanup,false).contains("Script execution evidence:** `cleanup_images.groovy`"),"without export preserve previous badge behavior");
        var out=new StringBuilder();var display=new StagePresentation(out::append);var first=savedEvidence(macro);
        String unchanged=first.toString();display.tool(execution("fiji_analysis_save_bundle",first.toString()));
        check(first.toString().equals(unchanged),"input receipt unchanged");
        display.tool(execution("fiji_analysis_save_bundle","{\"error\":\"export failed\"}"));
        display.tool(execution("fiji_analysis_save_bundle","invalid JSON"));display.validation(cleanup);display.finish("done",true);
        check(out.toString().contains("Task paused")&&out.substring(out.lastIndexOf("## ")).contains("delivered.ijm"),"failed exports and paused output retain last successful snapshot");
        out.setLength(0);display=new StagePresentation(out::append);display.tool(execution("fiji_analysis_save_bundle",first.toString()));
        first.getAsJsonObject("script_execution_evidence").addProperty("script_name","mutated.ijm");
        display.validation(cleanup);display.finish("done",false);
        check(!out.toString().contains("mutated.ijm")&&out.toString().contains("delivered.ijm"),"saved evidence remains an independent copy");
        out.setLength(0);display=new StagePresentation(out::append);display.tool(execution("fiji_analysis_save_bundle",savedEvidence(macro).toString()));
        display.tool(execution("fiji_analysis_save_bundle",savedEvidence(delivered).toString()));display.validation(cleanup);display.finish("done",false);
        shown=out.substring(out.lastIndexOf("## "));
        check(shown.contains("cellpose_count_final.groovy")&&!shown.contains("delivered.ijm"),"latest successful export wins across multiple exports");
        check(!badge(null,null,false).contains("snapshot evidence"),"new requests do not inherit another request's snapshot");
        System.out.println("PASS: exported-script badge survives cleanup, later edits, failed exports and pause; exact saved snapshot wording, latest export, unsupported language and request isolation");
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
    static void dialogStatuses(){
        var body=new StringBuilder();var display=new StagePresentation(body::append);
        String input="{\"run_id\":\"dialog\",\"script_name\":\"fixture.ijm\",\"status\":\"blocked_by_dialog\"}";
        display.tool(execution(input));
        check(body.toString().contains("Execution waiting for a dialog")&&!body.toString().contains("Execution error;"),"ordinary input dialog must not be called an error");
        String error="{\"run_id\":\"dialog\",\"script_name\":\"fixture.ijm\",\"status\":\"blocked_by_dialog\",\"error_dialog\":\"Undefined identifier in line 40\\nprint(d3s(1));\"}";
        display.tool(execution(error));display.tool(execution(error));
        check(body.toString().contains("Execution error; waiting for error dialog dismissal")&&body.toString().contains("Undefined identifier in line 40"),"explicit macro error dialog must show cause and remain blocked, including transition from normal dialog");
        check(body.indexOf("Execution error;")==body.lastIndexOf("Execution error;"),"repeated error-dialog polls must not spam progress");
        display.tool(execution("{\"ran_script\":{\"run_id\":\"nested\",\"status\":\"blocked_by_dialog\",\"error_dialog\":\"Array or string expected\"}}"));
        check(body.toString().contains("Array or string expected"),"nested receipts supported");
        var longError=new com.google.gson.JsonObject();longError.addProperty("run_id","long");longError.addProperty("status","blocked_by_dialog");longError.addProperty("error_dialog","x".repeat(5000));int before=body.length();display.tool(execution(longError.toString()));
        check(body.length()-before<650,"public reason stays bounded while original result remains intact");
        display.tool(execution("{\"run_id\":\"dialog\",\"status\":\"success\",\"error_dialog\":\"stale field\"}"));
        check(body.toString().contains("Execution finished: success")&&!body.toString().contains("stale field"),"terminal state remains authoritative");
        for(String empty:List.of("\"\"","null","{}")){
            var plain=new StringBuilder();new StagePresentation(plain::append).tool(execution("{\"run_id\":\"empty\",\"status\":\"blocked_by_dialog\",\"error_dialog\":"+empty+"}"));
            check(plain.toString().contains("Execution waiting for a dialog")&&!plain.toString().contains("Execution error;"),"empty or nontext error field is not a confirmed error");
        }
        System.out.println("PASS: explicit macro error-dialog cause, ordinary-dialog distinction, nested receipts, bounded detail, deduplication and unchanged terminal states");
    }
    static void literalReasons()throws Exception{
        var render=sc.fiji.llm.ui.ChatMessagePanel.class.getDeclaredMethod("renderMarkdownToSafeHtml",String.class);render.setAccessible(true);
        String reason="Undefined identifier: <getBitDepth> <contains> <analysisId> &lt;literal&gt; *stars* [link](https://example.invalid/) `quote`";
        for(String field:List.of("error_dialog","diagnostic","errors")){
            var value=new JsonObject();value.addProperty("run_id",field);
            value.addProperty("status",field.equals("error_dialog")?"blocked_by_dialog":"finished_with_errors");value.addProperty(field,reason);
            var result=execution(value.toString());
            var body=new StringBuilder();new StagePresentation(body::append).tool(result);
            String html=(String)render.invoke(null,body.toString());var doc=org.jsoup.Jsoup.parse(html);
            String shown=doc.select("code").text();
            check(shown.contains(reason.replace('`','\'')),"actual chat renderer must retain literal diagnostic: "+field+" got "+shown);
            check(doc.select("a").isEmpty(),"diagnostic must not become a Markdown link");
            check(result.result().equals(value.toString()),"original tool receipt is unchanged");
        }
        System.out.println("PASS: actual chat HTML rendering retains angle-bracket identifiers, entity text and Markdown punctuation in error_dialog/diagnostic/errors; original receipts unchanged");
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
