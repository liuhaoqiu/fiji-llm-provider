/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import javax.swing.*;
import com.google.gson.*;
import org.scijava.command.*;

/** Isolated process fixtures only: no user images, dialogs, preferences or API. */
public final class UiAutomationVerificationMain {
    static {net.imagej.patcher.LegacyInjector.preinit();}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static final UiAutomationTools.Registry registry=new UiAutomationTools.Registry();
    public static void main(String[] args)throws Exception{
        boolean gui=args.length>0&&args[0].equals("ui");System.setProperty("java.awt.headless",String.valueOf(!gui));
        try{
            numbers();metadata();
            if(gui){var context=MacroExecutionVerificationMain.fixtureContext();duplicateDialogs();numericDialog();imageCloses();
                System.out.println("PASS: actual duplicate-title dialogs by stable IDs, numeric commit/readback, stale-ID rejection, pending-close deduplication, save prompts and explicit temporary-image cleanup; source image retained");
                Runtime.getRuntime().halt(0);
            }
            System.out.println("PASS: numeric editor/model mismatch reproduced, committed values and range checks, real Cellpose-SAM input metadata without running it");
        }catch(Throwable t){t.printStackTrace();if(gui)Runtime.getRuntime().halt(1);throw t;}
    }
    static void numbers()throws Exception{
        UiAutomationTools.edt(()->{
            var spinner=new JSpinner(new SpinnerNumberModel(55,1,200,1));var field=((JSpinner.DefaultEditor)spinner.getEditor()).getTextField();
            field.setText("110");check(spinner.getValue().equals(55),"old text-only editing must reproduce stale model value");
            UiAutomationTools.setNumber(spinner,"110");check(spinner.getValue().equals(110)&&field.getValue().equals(110),"set and commit update both model/editor");
            for(String bad:List.of("201","55.5","NaN"))try{UiAutomationTools.setNumber(spinner,bad);throw new AssertionError("invalid value accepted");}catch(IllegalArgumentException|ArithmeticException expected){}
            check(spinner.getValue().equals(110),"invalid edit does not change model value");
            var decimal=new JSpinner(new SpinnerNumberModel(0.0,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,0.1));
            UiAutomationTools.setNumber(decimal,"0.4");check(decimal.getValue().equals(0.4),"unbounded floating controls remain supported");return null;
        });
    }
    @SuppressWarnings("unchecked")static void metadata()throws Exception{
        var info=new CommandInfo((Class<? extends Command>)Class.forName("fiji.plugin.appose.cellpose.cp4.CellposeSAMAppose"));
        CommandService service=(CommandService)java.lang.reflect.Proxy.newProxyInstance(CommandService.class.getClassLoader(),new Class<?>[]{CommandService.class},(p,m,a)->m.getName().equals("getCommands")?List.of(info):null);
        var data=UiAutomationTools.parameters(service,"Cellpose-SAM...");var names=new HashSet<String>();for(var row:data.getAsJsonArray("inputs"))names.add(row.getAsJsonObject().get("name").getAsString());
        check(names.containsAll(List.of("cell_diameter","min_size","image_title","chan0","chan1","chan2")),"actual SAM parameter names, not guessed display labels");
        check(!data.get("executed").getAsBoolean(),"metadata discovery must not execute Cellpose");
    }
    static JsonObject row(String title,String label)throws Exception{
        for(var item:registry.windows("").getAsJsonArray("windows")){
            var r=item.getAsJsonObject();if(!title.equals(r.get("title").getAsString()))continue;
            if(label==null||r.getAsJsonArray("labels").toString().contains(label))return r;
        }
        throw new AssertionError("fixture window not found: "+title);
    }
    static void duplicateDialogs()throws Exception{
        var left=new AtomicInteger();var right=new AtomicInteger();
        UiAutomationTools.edt(()->{
            for(String side:List.of("LEFT_FIXTURE","RIGHT_FIXTURE")){
                var dialog=new JDialog((Frame)null,"DUPLICATE_FIXTURE");var button=new JButton("Don't Save");
                button.addActionListener(e->{(side.startsWith("LEFT")?left:right).incrementAndGet();dialog.dispose();});
                dialog.add(new JLabel(side),BorderLayout.CENTER);dialog.add(button,BorderLayout.SOUTH);dialog.pack();dialog.setLocation(-20000,-20000);dialog.setVisible(true);
            }return null;
        });
        try{sc.fiji.llm.ui.AWTDialogUtils.respondToDialog("DUPLICATE_FIXTURE","Don't Save");throw new AssertionError("original title-based tool did not reproduce ambiguity");}catch(IllegalStateException expected){check(expected.getMessage().contains("Multiple visible dialogs"),"actual original ambiguity reproduced");}
        String id=row("DUPLICATE_FIXTURE","RIGHT_FIXTURE").get("window_id").getAsString();
        registry.respond(id,"Don't Save");UiAutomationTools.edt(()->null);
        check(right.get()==1&&left.get()==0,"stable ID selects only the requested same-title dialog");
        try{registry.respond(id,"Don't Save");throw new AssertionError("stale dialog accepted");}catch(IllegalArgumentException expected){}
        registry.respond(row("DUPLICATE_FIXTURE","LEFT_FIXTURE").get("window_id").getAsString(),"Don't Save");UiAutomationTools.edt(()->null);
    }
    static void numericDialog()throws Exception{
        var observed=new AtomicReference<Object>();var numberRef=new AtomicReference<JSpinner>();
        UiAutomationTools.edt(()->{
            var dialog=new JDialog((Frame)null,"NUMBER_FIXTURE");var panel=new JPanel();var spinner=new JSpinner(new SpinnerNumberModel(55,1,200,1));var label=new JLabel("Diameter");label.setLabelFor(spinner);var button=new JButton("OK");
            numberRef.set(spinner);
            button.addActionListener(e->{observed.set(spinner.getValue());dialog.dispose();});panel.add(label);panel.add(spinner);panel.add(new JLabel((Icon)null));panel.add(button);dialog.add(panel);dialog.pack();dialog.setLocation(-20000,-20000);dialog.setVisible(true);
            ((JSpinner.DefaultEditor)spinner.getEditor()).getTextField().setText("110");return null;
        });
        var window=row("NUMBER_FIXTURE",null);check(window.getAsJsonArray("numeric_controls").get(0).getAsJsonObject().get("value").getAsInt()==55,"snapshot reports actual model, not uncommitted text");
        String control=window.getAsJsonArray("numeric_controls").get(0).getAsJsonObject().get("control_id").getAsString();
        var committed=registry.number(window.get("window_id").getAsString(),control,"125");
        check(committed.getAsJsonArray("numeric_controls").get(0).getAsJsonObject().get("value").getAsInt()==125,"number tool commits and reads back the selected control by ID");
        UiAutomationTools.edt(()->{((JSpinner.DefaultEditor)numberRef.get().getEditor()).getTextField().setText("110");return null;});
        registry.respond(window.get("window_id").getAsString(),"OK");UiAutomationTools.edt(()->null);check(observed.get().equals(110),"affirmative dialog action commits the numeric editor first");
    }
    static JsonObject awaitClose(String runId,String expected)throws Exception{
        long deadline=System.nanoTime()+3_000_000_000L;JsonObject status=null;
        while(System.nanoTime()<deadline){status=registry.closeStatus(runId);if(expected.equals(status.get("status").getAsString()))return status;Thread.sleep(20);}
        throw new AssertionError("close did not become "+expected+": "+status);
    }
    static void imageCloses()throws Exception{
        var source=new AtomicReference<ij.ImagePlus>();var helper=new AtomicReference<ij.ImagePlus>();
        UiAutomationTools.edt(()->{
            var a=new ij.ImagePlus("SOURCE_FIXTURE",new ij.process.ByteProcessor(8,8));a.getProcessor().set(0,0,123);a.show();a.getWindow().setLocation(-20000,-20000);a.changes=true;source.set(a);
            var b=new ij.ImagePlus("QC_CLOSE_FIXTURE",new ij.process.ByteProcessor(8,8));b.show();b.getWindow().setLocation(-20000,-20000);b.changes=true;helper.set(b);return null;
        });
        int image=helper.get().getID();String run=registry.close(image,false).get("run_id").getAsString();var waiting=awaitClose(run,"blocked_by_dialog");
        var again=registry.close(image,false);check(run.equals(again.get("run_id").getAsString()),"repeat close reuses the pending run");
        check(again.getAsJsonArray("dialogs").size()==1,"one close creates only one save prompt");
        String dialog=waiting.getAsJsonArray("dialogs").get(0).getAsJsonObject().get("window_id").getAsString();registry.respond(dialog,"Don't Save");awaitClose(run,"closed");
        check(ij.WindowManager.getImage(image)==null,"closed state means actual image closure");check(registry.close(image,false).get("status").getAsString().equals("closed"),"completed close is idempotent");
        UiAutomationTools.edt(()->{var b=new ij.ImagePlus("QC_DISCARD_FIXTURE",new ij.process.ByteProcessor(8,8));b.show();b.getWindow().setLocation(-20000,-20000);b.changes=true;helper.set(b);return null;});
        String discard=registry.close(helper.get().getID(),true).get("run_id").getAsString();awaitClose(discard,"closed");
        check(ij.WindowManager.getImage(source.get().getID())==source.get()&&source.get().changes&&source.get().getProcessor().get(0,0)==123,"temporary cleanup must retain the source image and its changes/pixels");
    }
}
