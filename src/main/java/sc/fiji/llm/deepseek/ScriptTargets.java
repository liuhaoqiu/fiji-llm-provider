/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.lang.ref.WeakReference;
import java.util.*;
import com.google.gson.JsonObject;
import org.scijava.ui.swing.script.*;
import sc.fiji.llm.script.*;

/** Stable editor-object targets. Resolve, compare and edit in one EDT operation. */
final class ScriptTargets {
    record Snapshot(String name,String content,JsonObject evidence){}
    record Target(WeakReference<TextEditor> editor, WeakReference<EditorPane> pane) {}
    private static final Map<String,Target> targets=new HashMap<>(); // EDT only

    static JsonObject create(org.scijava.Context context,String name,String language,String content)throws Exception{
        var lang=context.service(org.scijava.script.ScriptService.class).getLanguageByName(language);
        if(lang==null)throw new IllegalArgumentException("Unknown installed script language: "+language);
        if(name.isBlank()||name.matches(".*[\\\\/:*?\"<>|].*")||name.equals(".")||name.equals(".."))throw new IllegalArgumentException("script_name must be a filename, not a path");
        int dot=name.lastIndexOf('.');if(dot<0||!lang.getExtensions().contains(name.substring(dot+1)))throw new IllegalArgumentException("Filename extension must match language "+language+": "+lang.getExtensions());
        return UiAutomationTools.edt(()->{
            var editor=sc.fiji.llm.ui.TextEditorUtils.getMostRecentVisibleEditor();
            // Script Editor 1.4.3 finishes evalScript using getEditorPane(), rather
            // than its original pane. Switching that editor to a new tab mid-run
            // can parse an uninitialized highlighter ("Writer is null"). Keep the
            // running editor's selected pane intact when creating another script.
            boolean separate=editor!=null&&!editor.getExecutingTasks().isEmpty();
            if(editor==null||separate){editor=new TextEditor(context);editor.setSize(700,500);editor.setVisible(true);}
            editor.newTab(content,language);editor.getEditorPane().setFileName(name);sc.fiji.llm.ui.TextEditorUtils.recordLastFocusedEditor(editor);
            int ei=TextEditor.instances.indexOf(editor),ti=sc.fiji.llm.ui.TextEditorUtils.getTabIndex(editor,editor.getTab());
            var out=read(new ScriptID(ei,ti).toString());out.remove("content");out.addProperty("language",language);out.addProperty("is_active",true);out.addProperty("saved_to_disk",false);
            if(separate)out.addProperty("editor_placement","separate_window_existing_editor_running");return out;
        });
    }

    static Snapshot snapshot(String handle,String expected)throws Exception{return UiAutomationTools.edt(()->{
        var target=targets.get(handle);if(target==null)throw new IllegalArgumentException("Unknown script_handle; use fiji_script_read_target");
        var editor=target.editor.get();var pane=target.pane.get();
        if(editor==null||pane==null||!editor.isShowing())throw new IllegalStateException("Script target is closed");
        for(var item:ScriptContextUtilities.getAvailableScripts())if(TextEditor.instances.get(item.getEditorIndex())==editor&&editor.getEditorPane(item.getTabIndex())==pane){
            if(!ScriptValidation.hash(pane.getText()).equalsIgnoreCase(expected))throw new IllegalStateException("Script changed; read its current hash before exporting");
            return new Snapshot(item.getScriptName(),pane.getText(),evidence(item));
        }
        throw new IllegalStateException("Script tab was closed");
    });}

    static JsonObject read(String id)throws Exception{return UiAutomationTools.edt(()->{
        if(!id.matches("[0-9]+:[0-9]+"))throw new IllegalArgumentException("Use script_id from fiji_script_list, e.g. 0:1");
        var parts=id.split(":");int editorIndex=Integer.parseInt(parts[0]),tabIndex=Integer.parseInt(parts[1]);
        var item=ScriptContextUtilities.buildScriptContextItem(editorIndex,tabIndex);
        var editor=TextEditor.instances.get(editorIndex);
        if(!editor.isShowing())throw new IllegalStateException("Script editor is not visible");
        var pane=editor.getEditorPane(tabIndex);
        targets.entrySet().removeIf(e->e.getValue().editor.get()==null||e.getValue().pane.get()==null);
        String handle="script-"+UUID.randomUUID();targets.put(handle,new Target(new WeakReference<>(editor),new WeakReference<>(pane)));
        var out=describe(handle,item);out.addProperty("content",pane.getText());return out;
    });}

    static JsonObject replace(String handle,String expected,String content)throws Exception{return UiAutomationTools.edt(()->{
        var target=targets.get(handle);
        if(target==null)throw new IllegalArgumentException("Unknown script_handle; use fiji_script_read_target first");
        var editor=target.editor.get();var pane=target.pane.get();
        if(editor==null||pane==null||!editor.isShowing())throw new IllegalStateException("Script target is closed; read a live target again");
        ScriptContextItem item=null;
        for(var candidate:ScriptContextUtilities.getAvailableScripts())
            if(TextEditor.instances.get(candidate.getEditorIndex())==editor && editor.getEditorPane(candidate.getTabIndex())==pane){item=candidate;break;}
        if(item==null)throw new IllegalStateException("Script tab was closed; no other tab was edited");
        String before=ScriptValidation.hash(pane.getText());
        if(!expected.matches("[0-9a-fA-F]{64}")||!before.equalsIgnoreCase(expected))
            throw new IllegalStateException("Script changed since it was read; no edit applied. Read the target again");
        int oldLines=pane.getLineCount();
        pane.beginAtomicEdit();try{pane.setText(content);}finally{pane.endAtomicEdit();}
        item=ScriptContextUtilities.buildScriptContextItem(item.getEditorIndex(),item.getTabIndex());
        var out=new JsonObject();var changed=new JsonObject();changed.addProperty("script_id",item.getId().toString());
        changed.addProperty("script_name",item.getScriptName());changed.addProperty("old_total_lines",oldLines);
        changed.addProperty("new_total_lines",pane.getLineCount());changed.addProperty("previous_sha256",before);
        out.add("replaced_content",changed);out.addProperty("script_handle",handle);
        out.add("script_execution_evidence",evidence(item));return out;
    });}

    private static JsonObject describe(String handle,ScriptContextItem item){
        var out=new JsonObject();out.addProperty("script_handle",handle);out.addProperty("script_id",item.getId().toString());
        out.addProperty("script_name",item.getScriptName());out.addProperty("current_sha256",ScriptValidation.hash(item.getScriptBody()));
        out.add("script_execution_evidence",evidence(item));return out;
    }
    private static JsonObject evidence(ScriptContextItem item){
        var out=ScriptValidation.evidence(item.getId().toString(),item.getScriptBody(),ScriptExecutionService.isMacroScript(item.getScriptName()));
        out.addProperty("script_name",item.getScriptName());return out;
    }
}
