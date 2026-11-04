/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import com.google.gson.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.data.message.*;
import dev.langchain4j.service.tool.ToolExecutor;
import org.scijava.Context;

/** Focused workflow helpers; no model call, shell access or scientific algorithm. */
final class WorkflowTools {
    static final String MARKER="[Fiji workflow evidence]";
    static final Map<String,Job> jobs=new ConcurrentHashMap<>();
    record Job(long started,Set<String> previous,FutureTask<JsonObject> future){}
    static Map<ToolSpecification,ToolExecutor> tools(Map<ToolSpecification,ToolExecutor> original,Supplier<Context> context,BoundedContext budget){
        var result=new LinkedHashMap<>(original);
        UiAutomationTools.add(result,"fiji_command_run_with_inputs","Run one SciJava command with an explicit JSON input object (exact names/types from fiji_command_parameters), no input harvester. image_id binds initialization to the intended image. Returns a run_id; poll fiji_command_inputs_status. Core postprocessors are skipped; inspect returned image IDs/results.",
            JsonObjectSchema.builder().addStringProperty("command").addStringProperty("inputs_json").addIntegerProperty("image_id").required("command","inputs_json","image_id").build(),a->start(context.get(),UiAutomationTools.string(a,"command"),JsonParser.parseString(UiAutomationTools.string(a,"inputs_json")).getAsJsonObject(),a.get("image_id").getAsInt()));
        UiAutomationTools.add(result,"fiji_command_inputs_status","Read the same explicit-input command run; returns actual command/input receipt or an error. Pending dialog targets include stable window_id. Do not start another run while this one is pending.",
            JsonObjectSchema.builder().addStringProperty("run_id").required("run_id").build(),a->status(UiAutomationTools.string(a,"run_id")));
        UiAutomationTools.add(result,"fiji_command_macro_template","Create a reproducible ImageJ macro scaffold for a 2D image command that creates exactly one label image: preserve active original, extract a channel, run exact typed parameters, select the new output by receipt, and measure labels against original intensity. Requires this provider plugin at runtime. Does not execute or certify the returned macro.",
            JsonObjectSchema.builder().addStringProperty("command").addStringProperty("inputs_json").addIntegerProperty("channel_number").required("command","inputs_json","channel_number").build(),a->{
                var info=CommandBridge.find(context.get(),UiAutomationTools.string(a,"command"));var out=new JsonObject();out.addProperty("content",macroTemplate(info.getClassName(),JsonParser.parseString(UiAutomationTools.string(a,"inputs_json")).getAsJsonObject(),a.get("channel_number").getAsInt()));out.addProperty("executed",false);return out;});
        UiAutomationTools.add(result,"fiji_image_extract_channel","Copy one 2D image channel into a new independent analysis image, retaining source pixels and provenance. RGB channel_number: 1=red, 2=green, 3=blue; grayscale: 1. Do not derive analysis input from a painted QC display.",
            JsonObjectSchema.builder().addIntegerProperty("image_id").addIntegerProperty("channel_number").required("image_id","channel_number").build(),a->{
                var image=ImageEvidence.extract(ij.WindowManager.getImage(a.get("image_id").getAsInt()),a.get("channel_number").getAsInt());UiAutomationTools.edt(()->{image.show();return null;});var r=new JsonObject();r.addProperty("image_id",image.getID());r.addProperty("title",image.getTitle());r.addProperty("source_image_id",a.get("image_id").getAsInt());r.addProperty("source_pixels_unchanged",true);r.addProperty("analysis_pixels_sha256",ImageEvidence.hash(image));return r;});
        UiAutomationTools.add(result,"fiji_label_measure","Count unique nonzero labels and measure areas/intensities from an explicitly separate source image/channel. For one 2D plane. Creates a non-destructive QC copy and an independent CSV/table, without clearing existing Results or ROI Manager. Signal coverage is not object recall; no size cutoff is applied.",
            JsonObjectSchema.builder().addIntegerProperty("source_image_id").addIntegerProperty("label_image_id").addIntegerProperty("source_channel").required("source_image_id","label_image_id","source_channel").build(),a->ImageEvidence.deliver(ij.WindowManager.getImage(a.get("source_image_id").getAsInt()),ij.WindowManager.getImage(a.get("label_image_id").getAsInt()),a.get("source_channel").getAsInt(),budget.archive.directory,true));
        UiAutomationTools.add(result,"fiji_script_validation_status","Compare the active editor script's exact SHA-256 with its last recorded macro execution. Editing invalidates the match. This does not certify fresh-state replay or scientific correctness.",JsonObjectSchema.builder().build(),a->ScriptValidation.active());
        for(var entry:new ArrayList<>(result.entrySet())){
            String name=entry.getKey().name();if(!Set.of("fiji_script_create","fiji_script_replace_content","fiji_script_rename","fiji_macro_run","fiji_macro_run_status","fiji_command_run","fiji_command_run_status").contains(name))continue;
            ToolExecutor prior=entry.getValue();result.put(entry.getKey(),(request,id)->{
                String text=prior.execute(request,id);try{
                    var object=JsonParser.parseString(text).getAsJsonObject();enrichDialogs(object);
                    if(name.startsWith("fiji_script_")||name.startsWith("fiji_macro_")){
                        JsonObject evidence=ScriptValidation.active();object.add("script_execution_evidence",evidence);
                    }return object.toString();
                }catch(Exception unavailable){return text;}
            });
        }
        return result;
    }
    static String macroTemplate(String command,JsonObject inputs,int channel){
        if(channel<1||channel>3)throw new IllegalArgumentException("Channel must be 1, 2 or 3");
        var values=inputs.deepCopy();values.remove("image_title");
        return "// Requires Fiji LLM Provider 0.5.10 or newer. Start on the original 2D image.\n"+
            "// Command must create exactly one new label image. Parameters remain your scientific choice.\n"+
            "sourceId = getImageID();\n"+
            "analysisId = call(\"sc.fiji.llm.deepseek.AnalysisBridge.extractChannel\", \"\"+sourceId, \""+channel+"\");\n"+
            "receipt = call(\"sc.fiji.llm.deepseek.CommandBridge.run\", "+new JsonPrimitive(command)+", "+new JsonPrimitive(values.toString())+", analysisId);\n"+
            "print(receipt);\nlabelsId = call(\"sc.fiji.llm.deepseek.CommandBridge.lastOutputImage\");\n"+
            "measurements = call(\"sc.fiji.llm.deepseek.AnalysisBridge.measureLabels\", \"\"+sourceId, labelsId, \""+channel+"\");\nprint(measurements);\n";
    }
    static synchronized JsonObject start(Context context,String command,JsonObject inputs,int image)throws Exception{
        if(context==null)throw new IllegalStateException("Command context unavailable");
        for(var existing:jobs.entrySet())if(!existing.getValue().future.isDone())throw new IllegalStateException("Command already running; poll run_id "+existing.getKey());
        var previous=new HashSet<String>();for(var e:UiAutomationTools.UI.windows("").getAsJsonArray("windows"))previous.add(e.getAsJsonObject().get("window_id").getAsString());
        String id="command-"+UUID.randomUUID();var future=new FutureTask<JsonObject>(()->CommandBridge.execute(context,command,inputs,image));jobs.put(id,new Job(System.currentTimeMillis(),previous,future));Thread thread=new Thread(future,"Fiji-command-inputs-"+id);thread.setDaemon(true);thread.start();return status(id);
    }
    static JsonObject status(String id)throws Exception{
        Job job=jobs.get(id);if(job==null)throw new IllegalArgumentException("Unknown explicit-input command run");JsonObject out;
        if(job.future.isDone()){try{out=job.future.get().deepCopy();}catch(Exception e){out=new JsonObject();out.addProperty("status","error");out.addProperty("error",String.valueOf(e.getCause()==null?e:e.getCause()));}}
        else {out=new JsonObject();out.addProperty("status","running");var dialogs=new JsonArray();for(var e:UiAutomationTools.UI.windows("").getAsJsonArray("windows")){var w=e.getAsJsonObject();if(w.get("modal").getAsBoolean()&&!job.previous.contains(w.get("window_id").getAsString()))dialogs.add(w);}if(!dialogs.isEmpty()){out.addProperty("status","blocked_by_dialog");out.add("dialogs",dialogs);}}
        out.addProperty("run_id",id);out.addProperty("elapsed_total_ms",System.currentTimeMillis()-job.started);return out;
    }
    static void enrichDialogs(JsonObject object)throws Exception{
        if(!object.has("dialogs")||object.getAsJsonArray("dialogs").isEmpty())return;
        var live=UiAutomationTools.UI.windows("").getAsJsonArray("windows");var targets=new JsonArray();
        for(var e:object.getAsJsonArray("dialogs")){var dialog=e.getAsJsonObject();String title=UiAutomationTools.string(dialog,"dialog_title");var matches=new ArrayList<JsonObject>();for(var w:live){var row=w.getAsJsonObject();if(row.get("modal").getAsBoolean()&&title.equals(UiAutomationTools.string(row,"title")))matches.add(row);}if(matches.size()==1)dialog.addProperty("window_id",matches.get(0).get("window_id").getAsString());for(var row:matches)targets.add(row);}
        if(!targets.isEmpty()){object.add("live_dialog_targets",targets);var tools=new JsonArray();tools.add("fiji_ui_dialog_respond_by_id");tools.add("fiji_ui_windows_identify");object.add("recommended_tools",tools);}
    }
    static ChatRequest prepare(ChatRequest request){
        var messages=new ArrayList<>(request.messages());if(messages.stream().anyMatch(m->m instanceof SystemMessage s&&s.text().contains(MARKER)))return request;
        String guidance="[Fiji workflow evidence] Prefer explicit-input command execution over repeated parameter dialogs. For a delivered 2D label-analysis macro, use fiji_command_macro_template to generate a source-preserving scaffold with typed runtime bridges, then run the exact final bytes. Extract analysis channels by image ID; QC images are display copies, never new raw inputs. Use fiji_label_measure with distinct source and label IDs; its intensity values come from the source, not label numbers. Preserve existing Results and ROI Manager. Before delivery inspect fiji_script_validation_status; editing invalidates the last execution match. Name the actual command/model and distinguish requested GPU settings from a reported device. Signal coverage and repeat counts are not accuracy/error bounds. Execution is not proof of clean-state reproducibility or scientific correctness.";
        for(int i=0;i<messages.size();i++)if(messages.get(i) instanceof SystemMessage s){messages.set(i,SystemMessage.from(s.text()+"\n\n"+guidance));return request.toBuilder().messages(messages).build();}messages.add(0,SystemMessage.from(guidance));return request.toBuilder().messages(messages).build();
    }
}
