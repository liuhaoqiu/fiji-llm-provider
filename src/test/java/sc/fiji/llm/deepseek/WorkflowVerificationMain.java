/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.google.gson.*;
import org.scijava.*;
import org.scijava.command.*;
import org.scijava.plugin.*;

/** Real module and ImageJ macro bridge, with synthetic commands/images only. */
public final class WorkflowVerificationMain {
    static{net.imagej.patcher.LegacyInjector.preinit();}
    static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
    static final AtomicInteger executions=new AtomicInteger();static volatile int seenDiameter;static volatile boolean seenGPU;
    static volatile boolean emitLabel;
    @Plugin(type=Command.class,name="Workflow fixture") public static class Fixture extends DynamicCommand implements Initializable {
        @Parameter int diameter=55;
        @Parameter boolean useGPU=false;
        @Parameter String image_title;
        @Override public void initialize(){diameter=7;image_title=ij.WindowManager.getCurrentImage().getTitle();}
        @Override public void run(){seenDiameter=diameter;seenGPU=useGPU;executions.incrementAndGet();if(emitLabel){var p=new ij.process.ShortProcessor(8,8);p.set(20,9);new ij.ImagePlus("FIXTURE_NEW_LABELS",p).show();}}
    }
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("ui")){System.setProperty("java.awt.headless","false");try{guiChecks();Runtime.getRuntime().halt(0);}catch(Throwable error){error.printStackTrace();Runtime.getRuntime().halt(1);}return;}
        System.setProperty("java.awt.headless","true");ij.macro.Interpreter.batchMode=true;
        imageChecks();scriptChecks();statusWaitChecks();
        try(var context=new Context(CommandService.class,org.scijava.module.ModuleService.class,org.scijava.task.TaskService.class,org.scijava.prefs.PrefService.class)){
            context.service(PluginService.class).addPlugin(new PluginInfo<Command>(Fixture.class,Command.class));
            var source=new ij.ImagePlus("EXPLICIT_INPUT_FIXTURE",new ij.process.ByteProcessor(8,8));source.show();ij.WindowManager.setTempCurrentImage(source);
            var values=JsonParser.parseString("{\"diameter\":110,\"useGPU\":true}").getAsJsonObject();
            var result=CommandBridge.execute(context,Fixture.class.getName(),values,source.getID());
            check(executions.get()==1&&seenDiameter==110&&seenGPU,"typed values applied after real dynamic initialization");
            check(result.get("input_pixels_unchanged").getAsBoolean(),"source unchanged by fixture command");
            check(result.getAsJsonObject("effective_inputs").get("diameter").getAsInt()==110,"actual effective parameter readback");
            for(String invalid:List.of("{\"Diameter\":110}","{\"diameter\":1.5}","{\"useGPU\":\"true\"}")){
                try{CommandBridge.execute(context,Fixture.class.getName(),JsonParser.parseString(invalid).getAsJsonObject(),source.getID());throw new AssertionError("invalid input accepted");}catch(IllegalArgumentException|ArithmeticException expected){}
            }
            check(executions.get()==1,"invalid inputs never reach command body");
            // An extracted channel may be preprocessed before a command runs on it; the receipt says so.
            int baseline=executions.get();
            var raw=ImageEvidence.extract(new ij.ImagePlus("PREPROCESS_FIXTURE",new ij.process.ColorProcessor(8,8)),2);raw.show();
            var untouched=CommandBridge.execute(context,Fixture.class.getName(),values.deepCopy(),raw.getID());
            check(!untouched.get("input_changed_since_extraction").getAsBoolean()&&!untouched.has("input_note"),"an unchanged extracted channel is reported as unchanged");
            check(!result.has("input_changed_since_extraction"),"an ordinary image carries no extraction statement");
            raw.getProcessor().invert();int ranBefore=executions.get();
            var inverted=CommandBridge.execute(context,Fixture.class.getName(),values.deepCopy(),raw.getID());
            check(executions.get()==ranBefore+1&&inverted.get("status").getAsString().equals("success"),"a command runs on an extracted channel that was inverted first");
            check(inverted.get("input_changed_since_extraction").getAsBoolean()&&inverted.get("input_note").getAsString().contains("preprocessed"),"the receipt states that the input was not raw source pixels");
            try{ImageEvidence.checkInput(raw);throw new AssertionError("modified channel accepted as a bundle source");}catch(IllegalArgumentException expected){}
            raw.setProperty(ImageEvidence.ROLE,"qc_display");
            try{CommandBridge.execute(context,Fixture.class.getName(),values.deepCopy(),raw.getID());throw new AssertionError("QC display accepted as command input");}catch(IllegalArgumentException expected){}
            check(executions.get()==baseline+2,"the refused QC display never reached the command body");
            raw.changes=false;raw.close();executions.set(baseline); // the checks below count runs on the first fixture image
            CommandBridge.register(context);
            String macro="reply = call(\"sc.fiji.llm.deepseek.CommandBridge.run\", \""+Fixture.class.getName()+"\", \"{\\\"diameter\\\":120,\\\"useGPU\\\":true}\", \""+source.getID()+"\"); print(reply);";
            var interpreter=new ij.macro.Interpreter();interpreter.setIgnoreErrors(true);interpreter.runBatchMacro(macro,source);
            check(interpreter.getErrorMessage()==null&&seenDiameter==120&&executions.get()==2,"real ImageJ call invokes explicit-input command bridge without harvesting");
            // Real installed Cellpose initialization only, no inference or parameter persistence.
            var info=new CommandInfo((Class<? extends Command>)Class.forName("fiji.plugin.appose.cellpose.cp3.CellposeAppose"));
            ij.macro.Interpreter.batchMode=true;source.show();
            ij.WindowManager.setTempCurrentImage(source);
            var cp=CommandBridge.prepare(context,info,JsonParser.parseString("{\"cp_model\":\"nuclei\",\"cell_diameter\":100,\"torchVersion\":\"cu126\",\"useGPU\":true}").getAsJsonObject(),source);
            check(cp.getInput("cell_diameter").equals(100)&&cp.getInput("torchVersion").equals("cu126")&&cp.getInput("useGPU").equals(true),"installed Cellpose dynamic initialization preserves explicit values");
            emitLabel=true;ij.macro.Interpreter.batchMode=true;source.show();ij.WindowManager.setTempCurrentImage(source);String before=ImageEvidence.hash(source);
            var replay=new ij.macro.Interpreter();replay.setIgnoreErrors(true);replay.runBatchMacro(WorkflowTools.macroTemplate(Fixture.class.getName(),values,1),source);
            check(replay.getErrorMessage()==null,"complete generated macro scaffold executes: "+replay.getErrorMessage());check(before.equals(ImageEvidence.hash(source)),"complete macro scaffold preserves original pixels");
        }
        System.out.println("PASS: explicit-input real command and macro call, post-initialization values, invalid-input rejection, installed Cellpose preparation, source-bound intensities, unique/gapped labels, QC immutability, independent CSV and script-version evidence that follows a script across tab positions; run-status tools wait within a bound; commands accept a preprocessed channel and say so");
        System.exit(0); // Isolated fixture only; upstream static maintenance threads may remain alive.
    }
    /** Fiji Chat's run-status tools answer at once; the wrapped ones wait for the run instead of being polled. */
    static void statusWaitChecks()throws Exception{
        var calls=new java.util.concurrent.atomic.AtomicInteger();var seen=new ArrayList<String>();
        long[] started={0},runMillis={0};String[] finished={"success"};boolean[] nested={false};
        dev.langchain4j.service.tool.ToolExecutor fake=(request,memoryId)->{
            calls.incrementAndGet();seen.add(request.arguments());
            String status=System.currentTimeMillis()-started[0]<runMillis[0]?"running":finished[0];
            return nested[0]?"{\"ran_script\":{\"run_id\":\"r\",\"status\":\""+status+"\"}}":"{\"run_id\":\"r\",\"status\":\""+status+"\"}";
        };
        var original=new LinkedHashMap<dev.langchain4j.agent.tool.ToolSpecification,dev.langchain4j.service.tool.ToolExecutor>();
        for(String name:List.of("fiji_macro_run_status","fiji_script_run_status","fiji_command_run_status","fiji_other_status"))
            original.put(dev.langchain4j.agent.tool.ToolSpecification.builder().name(name).description("Original description.")
                .parameters(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder().addStringProperty("run_id").required("run_id").build()).build(),fake);
        var budget=new BoundedContext(24000,1_048_576);var tools=WorkflowTools.tools(original,()->null,budget);
        for(var spec:tools.keySet()){
            if(!spec.name().endsWith("_status")||!original.keySet().stream().anyMatch(o->o.name().equals(spec.name())))continue;
            boolean waits=spec.parameters().properties().containsKey("wait_seconds");
            check(waits==!spec.name().equals("fiji_other_status"),"only the three run-status tools gain a wait: "+spec.name());
            check(spec.parameters().properties().containsKey("run_id")&&spec.parameters().required().equals(List.of("run_id"))&&spec.description().startsWith("Original description."),"original arguments and description are kept: "+spec.name());
        }
        java.util.function.BiFunction<String,String,JsonObject> ask=(name,arguments)->{calls.set(0);seen.clear();started[0]=System.currentTimeMillis();return DeliveryVerificationMain.call(tools,name,arguments);};
        runMillis[0]=700;long t=System.nanoTime();
        var done=ask.apply("fiji_macro_run_status","{\"run_id\":\"r\",\"wait_seconds\":5}");long ms=(System.nanoTime()-t)/1_000_000;
        check(done.get("status").getAsString().equals("success")&&calls.get()>=2&&ms>=600&&ms<4000,"one call waits for the run to finish instead of returning 'running': "+ms+" ms, "+calls.get()+" reads");
        check(seen.stream().noneMatch(a->a.contains("wait_seconds"))&&seen.stream().allMatch(a->a.contains("\"run_id\":\"r\"")),"the original tool receives its own arguments only");
        t=System.nanoTime();done=ask.apply("fiji_command_run_status","{\"run_id\":\"r\"}");
        check(done.get("status").getAsString().equals("success")&&calls.get()>=2,"waiting is the default when wait_seconds is omitted");
        runMillis[0]=60_000;t=System.nanoTime();
        var immediate=ask.apply("fiji_macro_run_status","{\"run_id\":\"r\",\"wait_seconds\":0}");ms=(System.nanoTime()-t)/1_000_000;
        check(immediate.get("status").getAsString().equals("running")&&calls.get()==1&&ms<1000,"wait_seconds=0 keeps the original immediate answer");
        t=System.nanoTime();var bounded=ask.apply("fiji_macro_run_status","{\"run_id\":\"r\",\"wait_seconds\":1}");ms=(System.nanoTime()-t)/1_000_000;
        check(bounded.get("status").getAsString().equals("running")&&ms>=900&&ms<4000,"the wait is bounded and reports a run that is still running: "+ms+" ms");
        budget.cancelled=true;t=System.nanoTime();var stopped=ask.apply("fiji_macro_run_status","{\"run_id\":\"r\"}");ms=(System.nanoTime()-t)/1_000_000;budget.cancelled=false;
        check(stopped.get("status").getAsString().equals("running")&&ms<1500,"Stop ends the wait: "+ms+" ms");
        runMillis[0]=0;finished[0]="blocked_by_dialog";t=System.nanoTime();
        var dialog=ask.apply("fiji_macro_run_status","{\"run_id\":\"r\"}");ms=(System.nanoTime()-t)/1_000_000;
        check(dialog.get("status").getAsString().equals("blocked_by_dialog")&&calls.get()==1&&ms<1000,"a dialog is reported at once");
        nested[0]=true;finished[0]="success";runMillis[0]=600;
        var script=ask.apply("fiji_script_run_status","{\"run_id\":\"r\"}");
        check(script.getAsJsonObject("ran_script").get("status").getAsString().equals("success")&&calls.get()>=2,"nested script status is waited for as well");
        nested[0]=false;runMillis[0]=60_000;
        for(String bad:List.of("{\"run_id\":\"r\",\"wait_seconds\":31}","{\"run_id\":\"r\",\"wait_seconds\":-1}","{\"run_id\":\"r\",\"wait_seconds\":\"soon\"}"))
            check(ask.apply("fiji_macro_run_status",bad).has("error")&&calls.get()==0,"an invalid wait is rejected before the run is queried: "+bad);
    }
    static void scriptChecks(){
        ScriptValidation.latest.clear();check(!ScriptValidation.evidence("0:0","print(1);").get("current_version_executed_successfully").getAsBoolean(),"new script unverified");
        ScriptValidation.record("0:0","print(1);","run-A","success");check(ScriptValidation.evidence("0:0","print(1);").get("current_version_executed_successfully").getAsBoolean(),"exact successful bytes recognized");
        check(ScriptValidation.evidence("0:0","print(2);").get("status").getAsString().equals("modified_since_last_run"),"editing invalidates previous success");
        check(!ScriptValidation.evidence("0:1","print(1);").get("current_version_executed_successfully").getAsBoolean(),"same bytes in another script not automatically verified");
        ScriptValidation.record("0:0","print(2);","run-B","finished_with_errors");check(ScriptValidation.evidence("0:0","print(2);").get("status").getAsString().equals("current_version_failed"),"failed run is not verification");
        // "editor:tab" is a position: closing earlier tabs moves a script to a lower one.
        var tabs=new HashMap<String,Object>();Object probe=new Object(),delivered=new Object(),fresh=new Object();
        var liveResolver=ScriptValidation.tabResolver;ScriptValidation.tabResolver=tabs::get;
        try{
            tabs.put("0:0",probe);tabs.put("0:2",delivered);
            ScriptValidation.record("0:0","print(\"probe\");","run-probe","success");ScriptValidation.record("0:2","print(\"delivered\");","run-delivered","success");
            tabs.clear();tabs.put("0:0",delivered);tabs.put("0:1",fresh);
            var moved=ScriptValidation.evidence("0:0","print(\"delivered\");");
            check(moved.get("status").getAsString().equals("executed_current_version")&&moved.get("last_run_id").getAsString().equals("run-delivered"),"execution evidence follows the script when closing other tabs changes its position: "+moved);
            check(ScriptValidation.evidence("0:1","print(\"new\");").get("status").getAsString().equals("not_run_in_this_session"),"a never-run script is not compared with the run of a script that previously held its position");
            check(!ScriptValidation.evidence("0:1","print(\"delivered\");").get("current_version_executed_successfully").getAsBoolean(),"same bytes in another live tab are still not automatically verified");
            check(ScriptValidation.evidence("0:0","print(\"edited\");").get("status").getAsString().equals("modified_since_last_run"),"editing the moved script still invalidates its own run");
        }finally{ScriptValidation.tabResolver=liveResolver;}
        var display=new StringBuilder();var stage=new StagePresentation(display::append);stage.validation(ScriptValidation.evidence("0:0","print(3);"));stage.finish("model report",false);check(display.toString().contains("no matching successful execution")&&display.toString().contains("model report"),"final UI independently discloses unverified script without changing model report");
    }
    static void imageChecks()throws Exception{
        var rgb=new ij.process.ColorProcessor(5,5);for(int i=0;i<25;i++)rgb.set(i,(10<<16)|(200<<8)|5);rgb.set(7,(30<<16)|(200<<8)|5);rgb.set(18,(90<<16)|(200<<8)|5);
        var source=new ij.ImagePlus("ORIGINAL_FIXTURE",rgb);String before=ImageEvidence.hash(source);
        var channel=ImageEvidence.extract(source,1);check(channel.getProcessor().get(7)==30&&before.equals(ImageEvidence.hash(source)),"red extraction is exact and preserves original");
        var lp=new ij.process.ShortProcessor(5,5);lp.set(6,2);lp.set(7,2);lp.set(18,9);var labels=new ij.ImagePlus("LABEL_FIXTURE",lp);
        var global=ij.measure.ResultsTable.getResultsTable();global.reset();global.setValue("Area",0,777);
        var measured=ImageEvidence.measure(source,labels,1);check(measured.summary.get("object_count").getAsInt()==2,"unique labels, not max label 9");
        check(measured.table.getValue("Mean_source_intensity",0)==20&&measured.table.getValue("Mean_source_intensity",1)==90,"source intensity, not label numbers 2 and 9");
        check(global.size()==1&&global.getValue("Area",0)==777,"existing Results untouched");
        check(before.equals(ImageEvidence.hash(source)),"QC does not draw into original pixels");check(measured.overlay.getOverlay()!=null,"QC uses non-destructive overlay");
        try{ImageEvidence.checkInput(measured.overlay);throw new AssertionError("QC admitted as input");}catch(IllegalArgumentException expected){}
        channel.getProcessor().set(0,99);try{ImageEvidence.checkInput(channel);throw new AssertionError("altered extraction admitted silently");}catch(IllegalArgumentException expected){}
        Path root=Path.of(System.getProperty("fiji.llm.contextArchiveRoot"));var delivered=ImageEvidence.deliver(source,labels,1,root.resolve("measurement-fixture"),false);
        check(Files.readString(Path.of(delivered.get("csv_file").getAsString())).contains("Mean_source_intensity"),"full independent CSV written");
        lp.set(0,7);check(ImageEvidence.measure(source,labels,1).summary.get("border_object_count").getAsInt()==1,"edge object explicitly reported");
    }
    static void guiChecks()throws Exception{
        var context=MacroExecutionVerificationMain.fixtureContext();
        var source=new ij.ImagePlus("SOURCE_QC_UI",new ij.process.ColorProcessor(32,32));source.getProcessor().set(0,0,0x102030);
        var lp=new ij.process.ShortProcessor(32,32);lp.set(200,7);lp.set(201,7);var labels=new ij.ImagePlus("LABEL_QC_UI",lp);
        UiAutomationTools.edt(()->{source.show();labels.show();source.getWindow().setLocation(-20000,-20000);labels.getWindow().setLocation(-20000,-20000);return null;});
        var manager=new ij.plugin.frame.RoiManager();manager.addRoi(new ij.gui.Roi(1,1,3,3));int old=manager.getCount();
        var global=ij.measure.ResultsTable.getResultsTable();global.reset();global.setValue("Area",0,777);String hash=ImageEvidence.hash(source);
        var delivered=ImageEvidence.deliver(source,labels,1,Path.of(System.getProperty("fiji.llm.contextArchiveRoot")).resolve("ui-measurement"),true);
        check(delivered.get("object_count").getAsInt()==1&&manager.getCount()==old&&global.getValue("Area",0)==777,"displayed QC/table retain existing ROI Manager and Results");
        check(hash.equals(ImageEvidence.hash(source)),"displayed overlay preserves source pixels");
        var dialog=new javax.swing.JDialog((java.awt.Frame)null,"WORKFLOW_DIALOG_FIXTURE",true);var button=new javax.swing.JButton("OK");button.addActionListener(e->dialog.dispose());dialog.add(button);dialog.pack();dialog.setLocation(-20000,-20000);java.awt.EventQueue.invokeLater(()->dialog.setVisible(true));
        long deadline=System.nanoTime()+3_000_000_000L;while(!dialog.isShowing()&&System.nanoTime()<deadline)Thread.sleep(10);
        var object=JsonParser.parseString("{\"dialogs\":[{\"dialog_title\":\"WORKFLOW_DIALOG_FIXTURE\"}]}").getAsJsonObject();WorkflowTools.enrichDialogs(object);
        String id=object.getAsJsonArray("dialogs").get(0).getAsJsonObject().get("window_id").getAsString();check(id.startsWith("window-"),"execution dialog enriched with registry ID");UiAutomationTools.UI.respond(id,"OK");UiAutomationTools.edt(()->null);
        check(!dialog.isShowing(),"recommended ID response closes exact dialog");
        System.out.println("PASS: real displayed independent QC and measurements, existing Results/ROI retention, unchanged source pixels and actionable execution-dialog IDs");
    }
}
