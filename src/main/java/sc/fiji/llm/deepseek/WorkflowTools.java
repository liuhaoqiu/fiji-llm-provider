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
        result.entrySet().removeIf(e->Set.of("fiji_script_create","fiji_script_replace_content").contains(e.getKey().name()));
        UiAutomationTools.add(result,"fiji_script_create","Create a new script with explicit filename, language and optional content. Defaults: script_name=script.ijm, language=ImageJ Macro, empty content. Returns stable script_handle/current_sha256 for subsequent fiji_script_replace_content. Does not save to disk.",
            JsonObjectSchema.builder().addStringProperty("script_name").addStringProperty("language").addStringProperty("content").build(),a->ScriptTargets.create(context.get(),a.has("script_name")?a.get("script_name").getAsString():"script.ijm",a.has("language")?a.get("language").getAsString():"ImageJ Macro",a.has("content")?a.get("content").getAsString():""));
        UiAutomationTools.add(result,"fiji_script_replace_content","Replace the exact script_handle returned by fiji_script_create or fiji_script_read_target. Requires expected_sha256 and content. Never falls back to the active tab; re-read after a conflict. Does not save to disk.",
            JsonObjectSchema.builder().addStringProperty("script_handle").addStringProperty("expected_sha256").addStringProperty("content").required("script_handle","expected_sha256","content").build(),a->ScriptTargets.replace(UiAutomationTools.string(a,"script_handle"),UiAutomationTools.string(a,"expected_sha256"),UiAutomationTools.string(a,"content")));
        UiAutomationTools.add(result,"fiji_analysis_save_bundle","Save the standard result set of a 2D label analysis: label TIFF, flattened QC, CSV with fixed columns, one ROI per nonzero label in rois.zip, the exact script snapshot and a hash/path manifest. Requires explicit source/label IDs and current script_handle/hash. Does not clear global Results or ROI Manager and never overwrites an earlier set. By default the set goes into results/ of this chat's output folder (see fiji_output_folder); a source that is an unchanged file on disk is named by path and hash instead of being copied, any other source image is saved with the set. Without such a folder, or with output_directory as an explicit parent, a new unique directory is used and the raw source TIFF is included.",
            JsonObjectSchema.builder().addIntegerProperty("source_image_id").addIntegerProperty("label_image_id").addIntegerProperty("source_channel").addStringProperty("script_handle").addStringProperty("expected_sha256").addStringProperty("output_directory").required("source_image_id","label_image_id","source_channel","script_handle","expected_sha256").build(),a->saveBundle(a,budget));
        UiAutomationTools.add(result,"fiji_script_save_file","Write one script to disk as a file: exactly the current bytes of script_handle (UTF-8), only if expected_sha256 still matches. Without output_file the script goes to scripts/<script name> in this chat's output folder. output_file, when given, is an absolute path whose folder already exists. An existing file is not replaced unless overwrite=true. Returns the saved path, byte count and SHA-256, and a warning if the script names this chat's output folder as a literal path. Does not save, rename or bind the editor tab, and does not run the script.",
            JsonObjectSchema.builder().addStringProperty("script_handle").addStringProperty("expected_sha256").addStringProperty("output_file").addBooleanProperty("overwrite").required("script_handle","expected_sha256").build(),a->saveScript(a,budget));
        UiAutomationTools.add(result,"fiji_output_folder","Return this chat's output folder, creating it on first use: Fiji_chat_output_<yyyymmdd>_<n> next to the input image given by image_id. There is one folder per chat and its number is assigned here. Unless the user names another location, write the files your scripts produce into script_output_directory, not next to the input image; standard results go to results/ through fiji_analysis_save_bundle and the final script to scripts/ through fiji_script_save_file. The extension itself keeps every script that was run, a step log, the final report and a manifest in this folder. Pass directory only when the user asked for a different parent folder.",
            JsonObjectSchema.builder().addIntegerProperty("image_id").addStringProperty("directory").build(),a->outputFolderTool(a,budget));

        UiAutomationTools.add(result,"fiji_command_run_with_inputs","Run one SciJava command with an explicit JSON input object (exact names/types from fiji_command_parameters), no input harvester. image_id binds initialization to the intended image. Returns a run_id; poll fiji_command_inputs_status. Core postprocessors are skipped; inspect returned image IDs/results.",
            JsonObjectSchema.builder().addStringProperty("command").addStringProperty("inputs_json").addIntegerProperty("image_id").required("command","inputs_json","image_id").build(),a->start(context.get(),UiAutomationTools.string(a,"command"),JsonParser.parseString(UiAutomationTools.string(a,"inputs_json")).getAsJsonObject(),a.get("image_id").getAsInt()));
        UiAutomationTools.add(result,"fiji_command_inputs_status","Wait for the same command run up to wait_seconds (default 20, integer 0-30). Return early on completion, error or a new modal dialog; do not rerun the command. Prefer bounded waiting to rapid repeated polls.",
            JsonObjectSchema.builder().addStringProperty("run_id").addIntegerProperty("wait_seconds").required("run_id").build(),a->waitStatus(UiAutomationTools.string(a,"run_id"),a.has("wait_seconds")?a.get("wait_seconds").getAsBigDecimal().intValueExact():20,()->budget.cancelled));
        UiAutomationTools.add(result,"fiji_script_read_target","Read script_id from fiji_script_list and return content, SHA-256 and a stable script_handle bound to the actual editor tab. Use before fiji_script_replace_target; active-tab changes do not change this target.",
            JsonObjectSchema.builder().addStringProperty("script_id").required("script_id").build(),a->ScriptTargets.read(UiAutomationTools.string(a,"script_id")));
        UiAutomationTools.add(result,"fiji_script_replace_target","Replace exactly the tab identified by script_handle from fiji_script_read_target, only if expected_sha256 still matches. The check/edit is atomic on the UI thread. Closed targets and concurrent edits fail without modifying another tab. Does not save or activate the target.",
            JsonObjectSchema.builder().addStringProperty("script_handle").addStringProperty("expected_sha256").addStringProperty("content").required("script_handle","expected_sha256","content").build(),a->ScriptTargets.replace(UiAutomationTools.string(a,"script_handle"),UiAutomationTools.string(a,"expected_sha256"),UiAutomationTools.string(a,"content")));
        UiAutomationTools.add(result,"fiji_command_macro_template","Create a reproducible ImageJ macro scaffold for a 2D image command that creates exactly one label image: preserve active original, extract a channel, run exact typed parameters, select the new output by receipt, and measure labels against original intensity. The returned macro calls this extension and does not run in a Fiji without it, so do not use it for a macro the user will keep or share unless they ask for it. Does not execute or certify the returned macro.",
            JsonObjectSchema.builder().addStringProperty("command").addStringProperty("inputs_json").addIntegerProperty("channel_number").required("command","inputs_json","channel_number").build(),a->{
                var info=CommandBridge.find(context.get(),UiAutomationTools.string(a,"command"));var out=new JsonObject();out.addProperty("content",macroTemplate(info.getClassName(),JsonParser.parseString(UiAutomationTools.string(a,"inputs_json")).getAsJsonObject(),a.get("channel_number").getAsInt()));out.addProperty("executed",false);out.addProperty("roi_policy","Template sets return_ROIs=false when that input is present; save canonical ROIs with fiji_analysis_save_bundle, without resetting ROI Manager");return out;});
        UiAutomationTools.add(result,"fiji_image_extract_channel","Copy one 2D image channel into a new independent analysis image, retaining source pixels and provenance. RGB channel_number: 1=red, 2=green, 3=blue; grayscale: 1. Do not derive analysis input from a painted QC display.",
            JsonObjectSchema.builder().addIntegerProperty("image_id").addIntegerProperty("channel_number").required("image_id","channel_number").build(),a->{
                var image=ImageEvidence.extract(ij.WindowManager.getImage(a.get("image_id").getAsInt()),a.get("channel_number").getAsInt());UiAutomationTools.edt(()->{image.show();return null;});var r=new JsonObject();r.addProperty("image_id",image.getID());r.addProperty("title",image.getTitle());r.addProperty("source_image_id",a.get("image_id").getAsInt());r.addProperty("source_pixels_unchanged",true);r.addProperty("analysis_pixels_sha256",ImageEvidence.hash(image));return r;});
        UiAutomationTools.add(result,"fiji_label_measure","Count unique nonzero labels and measure areas/intensities from an explicitly separate source image/channel. For one 2D plane. Creates a non-destructive QC copy and an independent CSV/table, without clearing existing Results or ROI Manager. Signal coverage is not object recall; no size cutoff is applied.",
            JsonObjectSchema.builder().addIntegerProperty("source_image_id").addIntegerProperty("label_image_id").addIntegerProperty("source_channel").required("source_image_id","label_image_id","source_channel").build(),a->ImageEvidence.deliver(ij.WindowManager.getImage(a.get("source_image_id").getAsInt()),ij.WindowManager.getImage(a.get("label_image_id").getAsInt()),a.get("source_channel").getAsInt(),budget.archive.directory,true));
        UiAutomationTools.add(result,"fiji_script_validation_status","Compare the active macro's exact SHA-256 with its recorded execution. Ordinary scripts return not_monitored, not a claim that they never ran; consult their execution receipts. Editing invalidates a macro match. Does not certify fresh-state replay or scientific correctness.",JsonObjectSchema.builder().build(),a->ScriptValidation.active());
        // Keep the bytes of every script this chat starts. They are read before the run, so the saved file is what ran.
        for(var entry:new ArrayList<>(result.entrySet())){
            String name=entry.getKey().name();if(!name.equals("fiji_macro_run")&&!name.equals("fiji_script_run"))continue;
            ToolExecutor prior=entry.getValue();result.put(entry.getKey(),(request,id)->{
                if(name.equals("fiji_macro_run"))MacroOptionCheck.drain(); // notes of earlier runs are not about this one
                String[] script=budget.output.enabled?targetScript(request.arguments()):null;
                if(script!=null)budget.output.ensure();
                String text=prior.execute(request,id);
                if(script!=null){String outcome=runOutcome(text);if(outcome!=null)budget.output.script(script[0],script[1],outcome);}
                return text;
            });
        }
        // Fiji Chat's run-status tools answer at once, so a model waiting for a 20 s run asks ten times and
        // each question is a full model request. Let one call wait, as fiji_command_inputs_status already does.
        for(var entry:new ArrayList<>(result.entrySet())){
            var spec=entry.getKey();if(!WAITABLE.contains(spec.name())||spec.parameters()==null||spec.parameters().properties().containsKey("wait_seconds"))continue;
            ToolExecutor prior=entry.getValue();result.remove(spec);
            var schema=JsonObjectSchema.builder().addProperties(spec.parameters().properties()).addIntegerProperty("wait_seconds","Optional integer 0-30, default 20: how long to wait while the run is still running.");
            if(spec.parameters().required()!=null)schema.required(spec.parameters().required());
            result.put(ToolSpecification.builder().name(spec.name()).description(spec.description()+" Waits up to wait_seconds (default 20, integer 0-30) while the run is still running and answers at once on completion, error or a dialog. Prefer one bounded wait to repeated polls.").parameters(schema.build()).build(),
                (request,id)->waitWhileRunning(prior,request,id,()->budget.cancelled));
        }
        for(var entry:new ArrayList<>(result.entrySet())){
            String name=entry.getKey().name();if(!Set.of("fiji_script_create","fiji_script_replace_content","fiji_script_rename","fiji_macro_run","fiji_macro_run_status","fiji_command_run","fiji_command_run_status").contains(name))continue;
            ToolExecutor prior=entry.getValue();result.put(entry.getKey(),(request,id)->{
                String text=prior.execute(request,id);try{
                    var object=JsonParser.parseString(text).getAsJsonObject();enrichDialogs(object);
                    if(name.equals("fiji_macro_run")||name.equals("fiji_macro_run_status")){
                        var notes=MacroOptionCheck.drain();
                        if(!notes.isEmpty()){var array=new JsonArray();notes.forEach(array::add);object.add("macro_option_warnings",array);}
                    }
                    if(!object.has("script_execution_evidence")&&(name.startsWith("fiji_script_")||name.startsWith("fiji_macro_"))){
                        // No active script to describe must not discard what was added above.
                        try{JsonObject evidence=ScriptValidation.active();object.add("script_execution_evidence",evidence);}
                        catch(Exception noActiveScript){if(!object.has("macro_option_warnings"))throw noActiveScript;}
                    }return object.toString();
                }catch(Exception unavailable){return text;}
            });
        }
        return result;
    }
    /** Name and text of the script a run tool is about to start: script_id when given, else the active tab. Null if it cannot be read. */
    static String[] targetScript(String arguments){
        try{
            var parsed=JsonParser.parseString(arguments==null||arguments.isBlank()?"{}":arguments).getAsJsonObject();
            String requested=UiAutomationTools.string(parsed,"script_id");
            return UiAutomationTools.edt(()->{
                int editor,tab;
                if(requested.matches("[0-9]+:[0-9]+")){editor=Integer.parseInt(requested.split(":")[0]);tab=Integer.parseInt(requested.split(":")[1]);}
                else{var active=sc.fiji.llm.ui.TextEditorUtils.getActiveScriptID();if(active==null)return null;editor=active.editorIndex;tab=active.tabIndex;}
                var item=sc.fiji.llm.script.ScriptContextUtilities.buildScriptContextItem(editor,tab);
                return new String[]{item.getScriptName(),item.getScriptBody()};
            });
        }catch(Exception unavailable){return null;}
    }
    /** Short outcome of a run result, or null when the run did not start. */
    static String runOutcome(String text){
        try{
            var value=JsonParser.parseString(text).getAsJsonObject();
            for(String key:List.of("ran_script","ran_macro","ran_command"))if(value.has(key)&&value.get(key).isJsonObject()){value=value.getAsJsonObject(key);break;}
            String status=UiAutomationTools.string(value,"status"),run=UiAutomationTools.string(value,"run_id");
            if(status.equals("busy")||value.has("started")&&value.get("started").isJsonPrimitive()&&!value.get("started").getAsBoolean())return null;
            return (status.isBlank()?"started":status)+(run.isBlank()?"":" ("+run+")");
        }catch(RuntimeException notJson){return "started";}
    }
    /** This chat's folder: next to the given image when it has a folder on disk, else next to an open image. Null if there is none. */
    static java.nio.file.Path outputFolder(BoundedContext budget,ij.ImagePlus image){
        if(!budget.output.enabled)return null;
        try{var directory=ChatOutput.imageDirectory(image);if(directory!=null)return budget.output.assign(directory,image);}
        catch(Exception unavailable){/* for example a read-only folder: fall back below */}
        return budget.output.ensure();
    }
    static JsonObject outputFolderTool(JsonObject a,BoundedContext budget){
        var out=new JsonObject();
        if(!budget.output.enabled){out.addProperty("status","switched_off");out.addProperty("note","The standard output folder is switched off in LLM Execution Settings. Save where the user asks.");return out;}
        ij.ImagePlus image=a.has("image_id")&&!a.get("image_id").isJsonNull()?ij.WindowManager.getImage(a.get("image_id").getAsInt()):null;
        String requested=UiAutomationTools.string(a,"directory");java.nio.file.Path home;
        try{home=requested.isBlank()?outputFolder(budget,image):budget.output.assign(java.nio.file.Path.of(requested),image);}
        catch(Exception failure){out.addProperty("status","unavailable");out.addProperty("reason","The folder could not be created in "+requested+": "+failure.getMessage());return out;}
        if(home==null){out.addProperty("status","unavailable");out.addProperty("reason","No open image has a writable folder on disk. Ask the user to save the image first, or to name a folder and pass it as directory.");return out;}
        try{
            out.addProperty("status","ready");out.addProperty("folder",home.toString());
            out.addProperty("script_output_directory",java.nio.file.Files.createDirectories(home.resolve("script_output")).toString());
            out.addProperty("scripts_directory",java.nio.file.Files.createDirectories(home.resolve("scripts")).toString());
            out.addProperty("standard_results_directory",home.resolve("results").toString());
        }catch(java.io.IOException failure){out.addProperty("status","unavailable");out.addProperty("reason","The folder exists but is not writable: "+failure.getMessage());return out;}
        out.addProperty("note","Files your scripts write belong in script_output_directory. A script that contains this folder as a literal path would write here again when it is reused later, so derive it or keep it as a setting if the script is meant for reuse. results/ is created by fiji_analysis_save_bundle.");
        return out;
    }
    static JsonObject saveBundle(JsonObject a,BoundedContext budget)throws Exception{return forModel(saveBundleFiles(a,budget));}
    /**
     * What the model is told about a saved set. The label-to-ROI list has one row per object and is already in
     * manifest.json; thousands of such rows would only fill the context of every later request.
     */
    static JsonObject forModel(JsonObject saved){
        if(!saved.has("roi_labels")||!saved.get("roi_labels").isJsonArray()||saved.getAsJsonArray("roi_labels").size()<=20)return saved;
        var rows=saved.getAsJsonArray("roi_labels");String example=rows.get(0).toString();saved.remove("roi_labels");
        saved.addProperty("roi_labels_note","roi_labels, one row per label such as "+example+", is in manifest_file ("+rows.size()+" rows) and is left out of this result.");
        return saved;
    }
    static JsonObject saveBundleFiles(JsonObject a,BoundedContext budget)throws Exception{
        var source=ij.WindowManager.getImage(a.get("source_image_id").getAsInt());var labels=ij.WindowManager.getImage(a.get("label_image_id").getAsInt());int channel=a.get("source_channel").getAsInt();
        var script=ScriptTargets.snapshot(UiAutomationTools.string(a,"script_handle"),UiAutomationTools.string(a,"expected_sha256"));
        String requested=UiAutomationTools.string(a,"output_directory");
        if(!requested.isBlank())return AnalysisBundle.save(source,labels,channel,script,java.nio.file.Path.of(requested));
        java.nio.file.Path home=outputFolder(budget,source);
        if(home==null)return AnalysisBundle.save(source,labels,channel,script,budget.archive.directory.resolve("exports"));
        java.nio.file.Path directory;
        try{directory=budget.output.fresh("results");}
        catch(java.io.IOException notWritable){return AnalysisBundle.save(source,labels,channel,script,budget.archive.directory.resolve("exports"));}
        var saved=AnalysisBundle.saveResults(source,labels,channel,script,directory);
        for(var file:saved.getAsJsonArray("files"))budget.output.wrote(java.nio.file.Path.of(file.getAsJsonObject().get("path").getAsString()),"standard result");
        budget.output.wrote(java.nio.file.Path.of(saved.get("manifest_file").getAsString()),"manifest of the standard results");
        saved.addProperty("output_folder",home.toString());return saved;
    }
    static JsonObject saveScript(JsonObject a,BoundedContext budget)throws Exception{
        String expected=UiAutomationTools.string(a,"expected_sha256"),file=UiAutomationTools.string(a,"output_file");
        var script=ScriptTargets.snapshot(UiAutomationTools.string(a,"script_handle"),expected);
        if(file.isBlank()){
            java.nio.file.Path home=budget.output.ensure();
            if(home==null)throw new IllegalArgumentException("This chat has no output folder; pass output_file as an absolute path");
            file=java.nio.file.Files.createDirectories(home.resolve("scripts")).resolve(java.nio.file.Path.of(script.name()).getFileName().toString()).toString();
        }
        var saved=ScriptTargets.write(script,expected,file,a.has("overwrite")&&!a.get("overwrite").isJsonNull()&&a.get("overwrite").getAsBoolean());
        budget.output.wrote(java.nio.file.Path.of(saved.get("file").getAsString()),"script saved for delivery");
        String literal=budget.output.literalFolderIn(script.content());
        if(literal!=null){
            saved.addProperty("warning","This script contains this chat's output folder as a literal path ("+literal+"). Run again later, or on another image, it would write into this same folder. If it is meant for reuse, derive the folder from the image or keep it as a setting at the top.");
            budget.output.warn("The saved script "+script.name()+" names this chat's output folder as a literal path");
        }
        return saved;
    }
    static final Set<String> WAITABLE=Set.of("fiji_macro_run_status","fiji_script_run_status","fiji_command_run_status");
    /** Repeats the original status read until the run leaves "running" or the wait ends. Never reruns or cancels the run. */
    static String waitWhileRunning(ToolExecutor prior,dev.langchain4j.agent.tool.ToolExecutionRequest request,Object memoryId,java.util.function.BooleanSupplier cancelled){
        int seconds=20;String arguments=request.arguments();
        try{
            var parsed=JsonParser.parseString(arguments==null||arguments.isBlank()?"{}":arguments).getAsJsonObject();
            if(parsed.has("wait_seconds")){
                if(!parsed.get("wait_seconds").isJsonNull())seconds=parsed.get("wait_seconds").getAsBigDecimal().intValueExact();
                parsed.remove("wait_seconds");arguments=parsed.toString(); // the original tool does not know this argument
            }
            if(seconds<0||seconds>30)throw new IllegalArgumentException();
        }catch(RuntimeException invalid){var error=new JsonObject();error.addProperty("error","wait_seconds must be an integer from 0 to 30");return error.toString();}
        var forwarded=dev.langchain4j.agent.tool.ToolExecutionRequest.builder().id(request.id()).name(request.name()).arguments(arguments).build();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(true){
            String text=prior.execute(forwarded,memoryId);
            if(seconds==0||!stillRunning(text)||cancelled.getAsBoolean()||javax.swing.SwingUtilities.isEventDispatchThread()||System.nanoTime()>=deadline)return text;
            try{Thread.sleep(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return text;}
        }
    }
    static boolean stillRunning(String text){
        try{
            var value=JsonParser.parseString(text).getAsJsonObject();
            for(String key:List.of("ran_script","ran_macro","ran_command"))if(value.has(key)&&value.get(key).isJsonObject()&&value.getAsJsonObject(key).has("status")){value=value.getAsJsonObject(key);break;}
            return value.has("status")&&value.get("status").isJsonPrimitive()&&value.get("status").getAsString().equals("running");
        }catch(RuntimeException notStatus){return false;}
    }
    static String macroTemplate(String command,JsonObject inputs,int channel){
        if(channel<1||channel>3)throw new IllegalArgumentException("Channel must be 1, 2 or 3");
        var values=inputs.deepCopy();values.remove("image_title");if(values.has("return_ROIs"))values.addProperty("return_ROIs",false);
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
    static JsonObject waitStatus(String id,int seconds,java.util.function.BooleanSupplier cancelled)throws Exception{
        if(seconds<0||seconds>30)throw new IllegalArgumentException("wait_seconds must be an integer from 0 to 30");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(true){
            if(cancelled.getAsBoolean())throw new CancellationException("Stopped by user; the command was not replayed or cancelled");
            var out=status(id);
            if(!UiAutomationTools.string(out,"status").equals("running")||seconds==0||javax.swing.SwingUtilities.isEventDispatchThread())return out;
            long remaining=deadline-System.nanoTime();if(remaining<=0)return out;
            try{jobs.get(id).future.get(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(200)),TimeUnit.NANOSECONDS);}
            catch(TimeoutException pending){}
            catch(ExecutionException finished){}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}
        }
    }
    static void enrichDialogs(JsonObject object)throws Exception{
        if(!object.has("dialogs")||object.getAsJsonArray("dialogs").isEmpty())return;
        var live=UiAutomationTools.UI.windows("").getAsJsonArray("windows");var targets=new JsonArray();
        for(var e:object.getAsJsonArray("dialogs")){var dialog=e.getAsJsonObject();String title=UiAutomationTools.string(dialog,"dialog_title");var matches=new ArrayList<JsonObject>();for(var w:live){var row=w.getAsJsonObject();if(row.get("modal").getAsBoolean()&&title.equals(UiAutomationTools.string(row,"title")))matches.add(row);}if(matches.size()==1)dialog.addProperty("window_id",matches.get(0).get("window_id").getAsString());for(var row:matches)targets.add(row);}
        if(!targets.isEmpty()){object.add("live_dialog_targets",targets);var tools=new JsonArray();tools.add("fiji_ui_dialog_respond_by_id");tools.add("fiji_ui_windows_identify");object.add("recommended_tools",tools);}
    }
    static ChatRequest prepare(ChatRequest request){
        var messages=new ArrayList<>(request.messages());if(messages.stream().anyMatch(m->m instanceof SystemMessage s&&s.text().contains(MARKER)))return request;
        String guidance="[Fiji workflow evidence] Output location: early in a task that will produce files, call fiji_output_folder with the input image. Unless the user names another place, files written by your scripts go into its script_output_directory, never next to the input image; fiji_analysis_save_bundle writes the standard results there and fiji_script_save_file the final script. Prefer explicit-input command execution over repeated parameter dialogs. Use fiji_command_inputs_status bounded waiting instead of rapid polls. The run-status tools for macros, scripts and commands wait too (wait_seconds, default 20): ask once and let it wait. fiji_script_create accepts filename/language/content and returns script_handle/current_sha256. fiji_script_replace_content now requires that handle and expected_sha256; it never edits an arbitrary active tab. For existing tabs first use fiji_script_read_target. Select the intended script before running it. Avoid resetting global ROI Manager; prefer return_ROIs=false and export one ROI per label with fiji_analysis_save_bundle. Before delivery save a bundle of the current script and explicit source/labels, then report the output folder and saved paths. Do not claim disk saving from editor tabs alone. To deliver a script file, call fiji_script_save_file with its script_handle and current hash; it writes those exact bytes and leaves the editor tab unsaved. A macro you deliver should run in any Fiji that has the plugins it calls: write ordinary macro code and do not make it depend on this extension. Extract analysis channels by image ID; QC images are display copies, never new raw inputs. Use fiji_label_measure with distinct source and label IDs; its intensity values come from the source, not label numbers. Preserve existing Results and ROI Manager. Before delivery inspect fiji_script_validation_status; editing invalidates the last execution match. Name the actual command/model and distinguish requested GPU settings from a reported device. Signal coverage and repeat counts are not accuracy/error bounds. Execution is not proof of clean-state reproducibility or scientific correctness.";
        for(int i=0;i<messages.size();i++)if(messages.get(i) instanceof SystemMessage s){messages.set(i,SystemMessage.from(s.text()+"\n\n"+guidance));return request.toBuilder().messages(messages).build();}messages.add(0,SystemMessage.from(guidance));return request.toBuilder().messages(messages).build();
    }
}
