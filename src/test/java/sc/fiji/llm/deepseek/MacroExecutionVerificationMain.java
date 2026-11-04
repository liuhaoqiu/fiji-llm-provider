/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import org.scijava.Context;
import org.scijava.ui.swing.script.TextEditor;
import org.scijava.ui.swing.script.JTextAreaWriter;
import sc.fiji.llm.script.*;
import sc.fiji.llm.ui.TextEditorUtils;
import javax.swing.*;
import java.awt.Window;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Isolated JVM only; synthetic images/macros, no user files or hosted API. */
public class MacroExecutionVerificationMain {
    static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
    public static void main(String[] args)throws Exception {
        try {
            var uiWatchdog=new Thread(()->{
                while(true) {
                    try { Thread.sleep(100); }
                    catch(InterruptedException e){return;}
                    for(Window w:Window.getWindows()) if(w.isShowing() && w instanceof java.awt.Dialog d && ("Error".equals(d.getTitle()) || "Save Changes?".equals(d.getTitle()))) {
                        System.err.println("Unexpected isolated test initialization dialog; stopping this test JVM.");
                        System.exit(1);
                    }
                }
            },"isolated-test-ui-watchdog"); uiWatchdog.setDaemon(true);uiWatchdog.start();
            scopeChecks();
            if(args.length>0) uiChecks(args[0]);
            System.out.println("PASS: macro evaluation completion, real child wait and editor maintenance separation");
            Runtime.getRuntime().halt(0);
        } catch(Throwable t){t.printStackTrace();Runtime.getRuntime().halt(1);}
    }
    static void scopeChecks()throws Exception {
        var maintenance=new JTextAreaWriter(new JTextArea(),null);
        try {
            var s=new MacroRunScope("maintenance");s.run(()->{});
            check(s.bodyFinished,"body finished independently of external writer updater");
            AtomicBoolean childDone=new AtomicBoolean();
            long start=System.nanoTime();
            new MacroRunScope("child").run(()->{
                new Thread(()->{try{Thread.sleep(250);}catch(InterruptedException e){throw new RuntimeException(e);}childDone.set(true);}).start();
            });
            check(childDone.get() && System.nanoTime()-start>200_000_000L,"real sleeping child must finish");
            try {new MacroRunScope("failure").run(()->{throw new IllegalStateException("fixture failure");});throw new AssertionError("failure swallowed");}
            catch(CompletionException expected){check(expected.getCause().getMessage().equals("fixture failure"),"failure preserved");}
            try {new MacroRunScope("child-failure").run(()->new Thread(()->{throw new IllegalStateException("child failure");}).start());throw new AssertionError("child failure swallowed");}
            catch(CompletionException expected){check(expected.getCause().getMessage().equals("child failure"),"child failure preserved");}
            var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
            Thread pending=new Thread(()->new MacroRunScope("unknown-child").run(()->new Thread(()->{
                entered.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}
            }).start()));
            pending.start();check(entered.await(2,TimeUnit.SECONDS),"child entered");Thread.sleep(50);
            check(pending.isAlive(),"unknown waiting child must not be discarded");release.countDown();pending.join(2000);check(!pending.isAlive(),"child release allows completion");
        } finally {maintenance.close();}
    }
    static void uiChecks(String mode)throws Exception {
        var required=new java.util.ArrayList<Class<? extends org.scijava.service.Service>>(java.util.List.of(ScriptExecutionService.class,
            org.scijava.platform.PlatformService.class, org.scijava.ui.UIService.class,
            org.scijava.script.ScriptHeaderService.class, org.scijava.batch.BatchService.class,
            org.scijava.io.IOService.class, org.scijava.ui.swing.script.LanguageSupportService.class,
            net.imagej.legacy.plugin.MacroExtensionAutoCompletionService.class,
            org.scijava.legacy.service.OpEnvironmentService.class, org.scijava.widget.WidgetService.class));
        for(String name:java.util.List.of("org.scijava.desktop.options.OptionsDesktop","org.scijava.run.console.RunArgument","net.imagej.batch.input.DatasetBatchInputProvider","io.scif.io.location.TestImgLocationResolver","io.scif.io.DatasetIOPlugin","org.scijava.table.io.DefaultTableIOPlugin","io.scif.io.RAIIOPlugin")) {
            for(Class<?> type=Class.forName(name);type!=null;type=type.getSuperclass())
                for(var field:type.getDeclaredFields())if(field.getAnnotation(org.scijava.plugin.Parameter.class)!=null && org.scijava.service.Service.class.isAssignableFrom(field.getType()))
                    required.add(field.getType().asSubclass(org.scijava.service.Service.class));
        }
        Context c=new Context(required);
        try {
            var svc=c.service(ScriptExecutionService.class);
            check(svc instanceof MacroExecutionService,"SciJava must select extension macro service");
            final TextEditor[] editor=new TextEditor[1];
            SwingUtilities.invokeAndWait(()->{
                editor[0]=new TextEditor(c);
                editor[0].newTab("print(\"MACRO_BODY_FINISHED\");", "ImageJ Macro");
                editor[0].getEditorPane().setFileName("fixture.ijm");
                editor[0].setSize(460,280);editor[0].setLocation(-20000,-20000);editor[0].setVisible(true);
                TextEditorUtils.recordLastFocusedEditor(editor[0]);
            });
            var id=new ScriptID(TextEditor.instances.indexOf(editor[0]),TextEditorUtils.getTabIndex(editor[0],editor[0].getTab()));
            if(mode.equals("ui")) {
                var image=new java.util.concurrent.atomic.AtomicReference<ij.ImagePlus>();
                var uiScope=new MacroRunScope("stack-window");var scopeDone=new CountDownLatch(1);
                var scopeFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread controller=new Thread(()->{
                    try {uiScope.run(()->{
                        var imp=new ij.ImagePlus("UI_STACK_FIXTURE",ij.ImageStack.create(32,32,3,8));
                        image.set(imp);imp.show();
                    });}catch(Throwable t){scopeFailure.set(t);}finally{scopeDone.countDown();}
                });controller.setDaemon(true);controller.start();
                check(scopeDone.await(5,TimeUnit.SECONDS) && scopeFailure.get()==null,"idle stack-window selector must not hold a completed macro");
                Thread[] children=new Thread[32];int count=uiScope.group.enumerate(children,true);boolean selector=false;
                for(int i=0;i<count;i++)if(MacroRunScope.idleMaintenanceWorker(children[i])) {
                    selector=true;children[i].setName("renamed-image-selector-fixture");
                    check(MacroRunScope.idleMaintenanceWorker(children[i]),"selector recognition must not depend on its name");
                }
                check(selector,"real ImageJ selector remains alive in the macro group");
                SwingUtilities.invokeAndWait(()->image.get().close());
                System.out.println("PASS: actual inherited ImageJ StackWindow selector, renamed idle worker and normal window closure");
            }
            if(mode.equals("cellpose") || mode.equals("large-cellpose") || mode.equals("baseline-cellpose")) {
                var runner=mode.equals("baseline-cellpose") ? c.service(DefaultScriptExecutionService.class) : svc;
                String macro="setBatchMode(true); newImage(\"CELLPOSE_FIXTURE\",\"8-bit black\",128,128,1); setForegroundColor(255,255,255); makeOval(25,25,24,24); run(\"Fill\"); makeOval(70,70,24,24); run(\"Fill\"); run(\"Select None\"); run(\"Cellpose...\", \"image_title=CELLPOSE_FIXTURE cp_model=nuclei custom_model= cell_diameter=24 cyto_channel=None nuclei_channel=1 min_size=15 normalize=true resample=true return_rois=true cellprob_threshold=0.0 flow_threshold=0.4 tile_overlap=0.1 niter=0 compute_flows=false shuffle=false labeltype=16-bit mode_3d=None stitch_threshold=0.1 flow3d_smooth=0 torchversion=cu126 usegpu=true\"); print(\"CELLPOSE_FIXTURE_DONE\");";
                if(mode.equals("large-cellpose")) macro="setBatchMode(true); newImage(\"RGB_CELLPOSE_FIXTURE\",\"RGB black\",3000,3000,1); setForegroundColor(255,0,0); for(y=100;y<2900;y+=180) for(x=100;x<2900;x+=180){makeOval(x,y,90,90); run(\"Fill\");} run(\"Select None\"); run(\"Duplicate...\",\"title=RGB_CELLPOSE_FIXTURE_cp\"); run(\"Make Composite\"); work=getTitle(); run(\"Cellpose...\", \"image_title=[\"+work+\"] cp_model=nuclei custom_model= cell_diameter=35 cyto_channel=None nuclei_channel=1 min_size=200 normalize=true resample=true return_rois=true cellprob_threshold=0.0 flow_threshold=0.4 tile_overlap=0.1 niter=0 compute_flows=false shuffle=false labeltype=16-bit mode_3d=None stitch_threshold=0.1 flow3d_smooth=0 torchversion=cu126 usegpu=true\"); n=roiManager(\"count\"); Table.create(\"FIXTURE_Cellpose_count\"); Table.set(\"Nuclei\",0,n,\"FIXTURE_Cellpose_count\"); Table.update(\"FIXTURE_Cellpose_count\"); print(\"CELLPOSE_FIXTURE_DONE\"); print(\"FIXTURE_ROI_COUNT=\"+n);";
                String fixture=System.getProperty("fiji.llm.cellposeFixture","");
                if(mode.equals("large-cellpose") && !fixture.isBlank()) {
                    String path=fixture.replace('\\','/').replace("\"","\\\"");
                    int replaceEnd=macro.indexOf("run(\"Select None\");");
                    macro="open(\""+path+"\"); run(\"Size...\",\"width=3000 height=3000 interpolation=Bilinear\"); rename(\"RGB_CELLPOSE_FIXTURE\"); "+macro.substring(replaceEnd);
                }
                setMacro(editor[0],macro);
                long start=System.nanoTime();var cp=runner.run(id,ScriptExecutionService.RunKind.MACRO,true).toJson();
                for(int n=0;n<200 && cp.get("status").getAsString().equals("running");n++) {
                    if(mode.equals("baseline-cellpose") && ij.IJ.getLog()!=null && ij.IJ.getLog().contains("CELLPOSE_FIXTURE_DONE"))break;
                    Thread.sleep(100);cp=runner.status(cp.get("run_id").getAsString(),ScriptExecutionService.RunKind.MACRO).toJson();
                }
                System.out.println("CELLPOSE_RESULT mode="+mode+" elapsed_ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)+" "+cp);
                if(mode.equals("cellpose") || mode.equals("large-cellpose")) {
                    check(cp.get("status").getAsString().matches("success|finished_with_warnings") && ij.IJ.getLog()!=null && ij.IJ.getLog().contains("CELLPOSE_FIXTURE_DONE"),"real Cellpose macro must finish");
                    if(mode.equals("large-cellpose") && !fixture.isBlank())
                        check(java.util.regex.Pattern.compile("FIXTURE_ROI_COUNT=[1-9][0-9]*").matcher(ij.IJ.getLog()).find(),"external large fixture must exercise nonempty ROI export");
                    setMacro(editor[0],"print(\"AFTER_CELLPOSE_NEXT_MACRO\");");
                    var next=runner.run(id,ScriptExecutionService.RunKind.MACRO,true).toJson();
                    check(next.get("status").getAsString().matches("success|finished_with_warnings"),"next macro after real Cellpose must start and finish");
                    System.out.println("PASS: real Cellpose terminal status and subsequent macro execution");
                } else {
                    check(cp.get("status").getAsString().equals("running") && ij.IJ.getLog()!=null && ij.IJ.getLog().contains("CELLPOSE_FIXTURE_DONE"),"baseline must reproduce completed-but-running defect");
                    System.out.println("REPRODUCED: original macro service remains running after actual Cellpose completion");
                }
                return;
            }
            var result=svc.run(id,ScriptExecutionService.RunKind.MACRO,true).toJson();
            System.out.println("FIRST_RESULT "+result);
            check(result.get("status").getAsString().matches("success|finished_with_warnings"),"simple macro must finish");
            var again=svc.run(id,ScriptExecutionService.RunKind.MACRO,true).toJson();
            System.out.println("SECOND_RESULT "+again);
            check(again.get("status").getAsString().matches("success|finished_with_warnings"),"second macro must start");
            // Check a real wait beyond the API's bounded initial wait.
            setMacro(editor[0],"wait(31000); print(\"LONG_MACRO_DONE\");");
            var longRun=svc.run(id,ScriptExecutionService.RunKind.MACRO,true).toJson();
            check(longRun.get("status").getAsString().equals("running") && longRun.get("wait_expired").getAsBoolean(),"long macro must remain running");
            try {svc.run(id,ScriptExecutionService.RunKind.MACRO,true);throw new AssertionError("duplicate run accepted");}
            catch(IllegalStateException expected){}
            check(await(svc,longRun.get("run_id").getAsString()).get("status").getAsString().equals("success"),"long macro finishes after real wait");
            setMacro(editor[0],"Dialog.create(\"FIXTURE_INPUT\"); Dialog.addString(\"Value\",\"ok\"); Dialog.show(); print(\"DIALOG_DONE\");");
            var dialog=svc.run(id,ScriptExecutionService.RunKind.MACRO,true).toJson();
            check(dialog.get("status").getAsString().equals("blocked_by_dialog"),"real dialog must remain blocked");
            dismiss("FIXTURE_INPUT");
            check(await(svc,dialog.get("run_id").getAsString()).get("status").getAsString().equals("success"),"dialog run completes after response");
            setMacro(editor[0],"undefinedFixtureFunction();");
            var bad=svc.run(id,ScriptExecutionService.RunKind.MACRO,true).toJson();
            check(bad.get("status").getAsString().equals("blocked_by_dialog"),"macro error must expose dialog");
            dismiss("Macro Error");
            check(await(svc,bad.get("run_id").getAsString()).get("status").getAsString().equals("finished_with_errors"),"dismissed error must not become success");
            check(svc.status("missing-fixture",ScriptExecutionService.RunKind.SCRIPT)==null,"ordinary script status delegates");
            System.out.println("PASS: real editor/module pipeline, consecutive runs, 31-second macro, duplicate prevention, input/error dialogs, core delegation");
        } finally {
            // The test JVM exits after this method; do not open legacy save
            // prompts while disposing synthetic image/editor windows.
            // This is a short-lived isolated JVM. Context.dispose would ask to
            // save synthetic unsaved tabs via the legacy window registry.
            // main exits immediately, releasing only this process's resources.
        }
    }
    static void setMacro(TextEditor e,String source)throws Exception {SwingUtilities.invokeAndWait(()->e.getEditorPane().setText(source));}
    static com.google.gson.JsonObject await(ScriptExecutionService s,String id)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline) {
            var r=s.status(id,ScriptExecutionService.RunKind.MACRO).toJson();
            if(!r.get("status").getAsString().equals("running"))return r;
            Thread.sleep(50);
        }
        throw new AssertionError("run did not reach a terminal state");
    }
    static void dismiss(String title)throws Exception {
        SwingUtilities.invokeAndWait(()->{
            boolean found=false;
            for(Window w:Window.getWindows())if(w instanceof ij.gui.GenericDialog d && d.isVisible() && title.equals(d.getTitle())) {
                found=true;var button=d.getButtons()[0];d.actionPerformed(new java.awt.event.ActionEvent(button,java.awt.event.ActionEvent.ACTION_PERFORMED,"OK"));
            }
            check(found,"fixture dialog must be visible");
        });
    }
}
