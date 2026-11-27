/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;
import ij.ImagePlus;
import ij.process.*;

/** The per-chat output folder: numbering, what is recorded, what is never overwritten, and failures that must stay harmless. */
public final class OutputFolderVerificationMain {
    static{net.imagej.patcher.LegacyInjector.preinit();}
    static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
    static final String TODAY=LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);

    public static void main(String[] args){try{
        System.setProperty("java.awt.headless","true");
        Path root=Files.createDirectories(Path.of(System.getProperty("fiji.llm.contextArchiveRoot")).resolve("output-folder-fixtures"));
        numbering(root);records(root);unavailable(root);tools(root);
        System.out.println("PASS: one numbered output folder per chat and input folder, claimed atomically and never reused; executed scripts, steps, report and manifest saved without overwriting; standard results that copy the source only when it is not a file; files in folders a script created next to the input; compact result for long ROI lists; literal-folder warning; guidance for extension-independent macros; switched off, unsaved image, unwritable folder and failed writes leave the analysis untouched");
        Runtime.getRuntime().halt(0);
    }catch(Throwable failure){failure.printStackTrace();Runtime.getRuntime().halt(1);}}

    /** An image as if opened from a file in that folder. */
    static ImagePlus opened(Path folder,String name,ImageProcessor pixels)throws Exception{
        var image=new ImagePlus(name,pixels);Path file=folder.resolve(name);
        check(new ij.io.FileSaver(image).saveAsTiff(file.toString()),"fixture image saved");
        var info=new ij.io.FileInfo();info.directory=folder.toString()+java.io.File.separator;info.fileName=name;image.setFileInfo(info);
        return image;
    }

    static void numbering(Path root)throws Exception{
        Path input=Files.createDirectories(root.resolve("细胞 图像 input"));
        LocalDate day=LocalDate.of(2026,11,27);
        Path first=ChatOutput.allocate(input,day);
        check(first.getFileName().toString().equals("Fiji_chat_output_20261127_1")&&Files.isDirectory(first),"first folder of the day is _1, also in a path with Chinese characters and spaces");
        check(ChatOutput.allocate(input,day).getFileName().toString().endsWith("_2"),"an existing _1 leads to _2 even though _1 is empty");
        Files.createDirectory(input.resolve("Fiji_chat_output_20261127_7"));Files.createDirectory(input.resolve("Fiji_chat_output_20261127_draft"));
        Files.createDirectory(input.resolve("Fiji_chat_output_20261126_40"));Files.writeString(input.resolve("Fiji_chat_output_20261127_9"),"a file, not a folder");
        check(ChatOutput.allocate(input,day).getFileName().toString().endsWith("_10"),"the number continues after the highest one of that day; other days and other names are ignored");
        check(ChatOutput.allocate(input,LocalDate.of(2026,11,28)).getFileName().toString().equals("Fiji_chat_output_20261128_1"),"a new day starts at _1");
        // several chats asking at the same moment must each get their own folder
        Path busy=Files.createDirectories(root.resolve("concurrent"));var pool=Executors.newFixedThreadPool(8);var claimed=new ArrayList<Future<Path>>();
        for(int i=0;i<24;i++)claimed.add(pool.submit(()->ChatOutput.allocate(busy,day)));
        var names=new TreeSet<String>();for(var one:claimed)names.add(one.get(20,TimeUnit.SECONDS).getFileName().toString());pool.shutdownNow();
        check(names.size()==24,"concurrent requests never share a folder: "+names.size()+" distinct of 24");
        try{ChatOutput.allocate(root.resolve("missing"),day);throw new AssertionError("missing parent accepted");}catch(java.io.IOException expected){}
        check(!Files.exists(root.resolve("missing")),"a missing parent folder is not created");
    }

    static void records(Path root)throws Exception{
        Path input=Files.createDirectories(root.resolve("records"));var image=opened(input,"cells.tif",new ByteProcessor(8,8));
        var chat=new ChatOutput(Files.createDirectories(root.resolve("archive-records")));chat.label="DeepSeek / fixture-model";
        // before any folder exists, records go to the local archive and nothing appears next to the input
        chat.step("`fiji_image_details` success");chat.script("probe.ijm","print(1);","success (run-1)");
        check(chat.folder()==null&&Files.list(input).count()==1,"recording alone does not create a folder next to the input");
        Path home=chat.assign(input,image);
        check(home.getFileName().toString().equals(ChatOutput.PREFIX+TODAY+"_1")&&home.getParent().equals(input),"folder sits next to the input image and carries today's date");
        check(chat.assign(input,image).equals(home),"one chat keeps one folder for an input folder");
        check(Files.readString(home.resolve("process/scripts/01_probe.ijm")).equals("print(1);")&&Files.readString(home.resolve("process/steps.md")).contains("fiji_image_details"),"records made before the folder existed are moved into it");
        chat.script("probe.ijm","print(1);","success (run-2)");chat.script("probe.ijm","print(2);","finished_with_errors (run-3)");chat.script("a/b:c?.groovy","println 1","success");
        var saved=new TreeSet<String>();try(var list=Files.list(home.resolve("process/scripts"))){list.forEach(p->saved.add(p.getFileName().toString()));}
        check(saved.equals(new TreeSet<>(List.of("01_probe.ijm","02_probe.ijm","03_a_b_c_.groovy"))),"each distinct executed version is kept once, in run order, with a safe file name: "+saved);
        check(Files.readString(home.resolve("process/scripts/02_probe.ijm")).equals("print(2);"),"an edited and rerun script keeps both versions");
        String steps=Files.readString(home.resolve("process/steps.md"));
        check(steps.contains("run-2")&&steps.contains("run-3")&&steps.lines().filter(l->l.startsWith("- ")).count()==5,"every run is a line in the step log, including reruns of unchanged content");
        chat.report("### 中文\n\n结果\n\n### English\n\nResult");chat.report("second answer");
        check(Files.readString(home.resolve("report.md")).startsWith("### 中文")&&Files.readString(home.resolve("report_2.md")).equals("second answer"),"a later answer never replaces the first report");
        Files.writeString(Files.createDirectories(home.resolve("script_output")).resolve("mask.tif"),"written by a macro");
        Files.writeString(input.resolve("stray_output.csv"),"written next to the input");
        Files.writeString(Files.createDirectories(input.resolve("Macro_results").resolve("deeper")).resolve("mask.tif"),"written by a delivered macro into its own folder");
        Files.writeString(Files.createDirectories(input.resolve(ChatOutput.PREFIX+"20200101_1")).resolve("other.txt"),"another chat");
        chat.report("third");
        var manifest=JsonParser.parseString(Files.readString(home.resolve("manifest.json"))).getAsJsonObject();
        var byPath=new HashMap<String,JsonObject>();for(var row:manifest.getAsJsonArray("files"))byPath.put(row.getAsJsonObject().get("path").getAsString(),row.getAsJsonObject());
        check(manifest.get("format").getAsString().equals(ChatOutput.FORMAT)&&manifest.get("final").getAsBoolean()&&manifest.get("assistant").getAsString().contains("fixture-model")&&manifest.get("full_record").getAsString().contains("archive-records"),"manifest names the format, the assistant and the full local record");
        check(byPath.get("report.md").get("written_by").getAsString().equals("extension")&&byPath.get("process/scripts/01_probe.ijm").get("written_by").getAsString().equals("extension")&&byPath.get("script_output/mask.tif").get("written_by").getAsString().equals("script or user"),"manifest tells extension files from files a script wrote");
        check(byPath.get("report.md").get("sha256").getAsString().equals(AnalysisBundle.fileHash(home.resolve("report.md")))&&byPath.get("report.md").get("bytes").getAsLong()==Files.size(home.resolve("report.md")),"final manifest carries size and hash");
        var inputRow=manifest.getAsJsonArray("inputs").get(0).getAsJsonObject();
        check(Path.of(inputRow.get("path").getAsString()).equals(input.resolve("cells.tif"))&&inputRow.get("sha256").getAsString().equals(AnalysisBundle.fileHash(input.resolve("cells.tif"))),"the input is named by path and hash, not copied");
        check(!Files.exists(home.resolve("cells.tif")),"the input image is not copied into the folder");
        check(manifest.getAsJsonArray("new_files_next_to_the_input").toString().contains("stray_output.csv")&&!manifest.getAsJsonArray("new_files_next_to_the_input").toString().contains("cells.tif"),"files a script left next to the input are listed, the input itself is not");
        String near=manifest.getAsJsonArray("new_files_next_to_the_input").toString();
        check(near.contains("Macro_results")&&near.contains("deeper")&&near.contains("mask.tif"),"files in a folder a script created next to the input are listed: "+near);
        check(!near.contains(ChatOutput.PREFIX)&&!manifest.has("new_files_next_to_the_input_truncated"),"chat output folders are not looked into: "+near);
        // the result a model receives does not repeat thousands of derivable ROI rows; a short list stays
        var many=new JsonObject();var manyRows=new JsonArray();for(int n=1;n<=4306;n++){var row=new JsonObject();row.addProperty("label",n);row.addProperty("zip_entry","label-"+n+".roi");manyRows.add(row);}
        many.addProperty("roi_count",4306);many.add("roi_labels",manyRows);many.addProperty("manifest_file","m.json");int before=many.toString().length();
        var told=WorkflowTools.forModel(many);
        check(!told.has("roi_labels")&&told.get("roi_labels_note").getAsString().contains("label-1.roi")&&told.get("roi_labels_note").getAsString().contains("4306 rows")&&told.get("roi_count").getAsInt()==4306&&told.toString().length()<600&&before>150000,"a long ROI list is named, not repeated: "+before+" -> "+told.toString().length()+" characters");
        var few=new JsonObject();var fewRows=new JsonArray();fewRows.add(manyRows.get(0));few.add("roi_labels",fewRows);
        check(WorkflowTools.forModel(few).getAsJsonArray("roi_labels").size()==1&&!few.has("roi_labels_note"),"a short ROI list is returned as before");
        // a second input folder in the same chat gets its own folder; a second chat gets the next number
        Path other=Files.createDirectories(root.resolve("records-other"));
        check(chat.assign(other,opened(other,"b.tif",new ByteProcessor(4,4))).getParent().equals(other),"a second input folder gets its own output folder");
        check(new ChatOutput(Files.createDirectories(root.resolve("archive-second"))).assign(input,image).getFileName().toString().endsWith("_2"),"another chat on the same day gets the next number");
        // literal folder path in a delivered script
        check(chat.literalFolderIn("dir = \""+home.toString().replace("\\","\\\\")+"\\\\script_output\\\\\";")!=null&&chat.literalFolderIn("dir = \""+home.toString().replace("\\","/")+"/x\";")!=null,"a script that names this chat's folder literally is recognised with escaped or forward slashes");
        check(chat.literalFolderIn("dir = getDirectory(\"image\");")==null,"a script that derives its folder is not flagged");
        Path r1=chat.fresh("results"),r2=chat.fresh("results");
        check(r1.getFileName().toString().equals("results")&&r2.getFileName().toString().equals("results_2"),"a second standard result set gets its own directory");
    }

    static void unavailable(Path root)throws Exception{
        Path input=Files.createDirectories(root.resolve("unavailable"));var image=opened(input,"c.tif",new ByteProcessor(4,4));
        var off=new ChatOutput(Files.createDirectories(root.resolve("archive-off")));off.enabled=false;
        off.step("ignored");off.script("x.ijm","print(1);","success");off.report("ignored");
        check(off.ensure()==null&&Files.list(input).count()==1&&!Files.exists(root.resolve("archive-off").resolve("output-staging")),"switched off: no folder, no staging records");
        try{off.assign(input,image);throw new AssertionError("assigned although switched off");}catch(IllegalStateException expected){}
        check(ChatOutput.imageDirectory(new ImagePlus("unsaved",new ByteProcessor(2,2)))==null&&ChatOutput.imageDirectory(null)==null,"an unsaved image has no folder to put results next to");
        // a parent that cannot hold a folder: recording must fail quietly and keep working from the archive
        Path blocked=Files.writeString(root.resolve("a-file-not-a-folder"),"x");
        var chat=new ChatOutput(Files.createDirectories(root.resolve("archive-blocked")));
        try{chat.assign(blocked,null);throw new AssertionError("a file accepted as parent folder");}catch(java.io.IOException expected){}
        chat.script("kept.ijm","print(3);","success");chat.step("still recorded");chat.report("no folder, so no report file");
        check(Files.readString(root.resolve("archive-blocked/output-staging/process/scripts/01_kept.ijm")).equals("print(3);"),"without a folder the process records stay in the local archive");
        // a write that fails must not reach the caller
        var broken=new ChatOutput(Files.writeString(root.resolve("archive-is-a-file"),"x"));
        broken.step("cannot be written");broken.script("y.ijm","print(4);","success");broken.wrote(root.resolve("nowhere.txt"),"x");broken.warn("w");
        check(broken.folder()==null,"failed record writes are swallowed and reported once on stderr");
    }

    static JsonObject call(Map<ToolSpecification,ToolExecutor> tools,String name,String arguments){return DeliveryVerificationMain.call(tools,name,arguments);}

    static void tools(Path root)throws Exception{
        Path input=Files.createDirectories(root.resolve("tools"));
        var source=opened(input,"plate.tif",new ByteProcessor(8,8));var labels=new ImagePlus("labels",new ShortProcessor(8,8));
        for(int y=1;y<4;y++)for(int x=1;x<4;x++)labels.getProcessor().set(x,y,1);labels.getProcessor().set(6,6,2);
        var ran=new ArrayList<String>();String[] next={"{\"run_id\":\"r1\",\"status\":\"success\",\"script_name\":\"pipeline.ijm\"}"};
        var original=new LinkedHashMap<ToolSpecification,ToolExecutor>();
        original.put(ToolSpecification.builder().name("fiji_macro_run").description("fixture").parameters(JsonObjectSchema.builder().build()).build(),(request,id)->{ran.add(request.arguments());return next[0];});
        var budget=new BoundedContext(24000,1_048_576);
        var tools=WorkflowTools.tools(original,()->null,budget);
        // no editor is open here: the run tool must behave exactly as before
        check(call(tools,"fiji_macro_run","{}").get("status").getAsString().equals("success")&&ran.size()==1,"the run tool still runs and returns its result when no script can be read");
        check(call(tools,"fiji_output_folder","{}").get("status").getAsString().equals("unavailable"),"without a saved image the folder tool says so instead of guessing a place");
        source.show();ij.WindowManager.setTempCurrentImage(source);
        var ready=call(tools,"fiji_output_folder","{\"image_id\":"+source.getID()+"}");
        Path home=Path.of(ready.get("folder").getAsString());
        check(ready.get("status").getAsString().equals("ready")&&home.getParent().equals(input)&&home.getFileName().toString().equals(ChatOutput.PREFIX+TODAY+"_1"),"folder tool creates the folder next to the given image: "+ready);
        check(Files.isDirectory(Path.of(ready.get("script_output_directory").getAsString()))&&Files.isDirectory(Path.of(ready.get("scripts_directory").getAsString()))&&!Files.exists(home.resolve("results")),"script_output/ and scripts/ exist for scripts to write into; results/ is left to the standard save");
        check(Path.of(call(tools,"fiji_output_folder","{}").get("folder").getAsString()).equals(home),"asking again returns the same folder");
        // standard results: into results/, without a copy of the source, never replacing an earlier set
        String script="print(\"PIPELINE\");";var snapshot=new ScriptTargets.Snapshot("pipeline.ijm",script,ScriptValidation.evidence("7:7",script));
        var first=AnalysisBundle.saveResults(source,labels,1,snapshot,budget.output.fresh("results"));
        Path results=Path.of(first.get("bundle_directory").getAsString());
        check(results.equals(home.resolve("results"))&&Files.exists(results.resolve("labels.tif"))&&Files.exists(results.resolve("measurements.csv"))&&Files.exists(results.resolve("rois.zip"))&&Files.exists(results.resolve("qc.tif"))&&Files.exists(results.resolve("script/pipeline.ijm")),"standard result files with fixed names");
        check(!Files.exists(results.resolve("source.tif"))&&!first.get("source_copied").getAsBoolean()&&first.get("source_pixels_sha256").getAsString().equals(ImageEvidence.hash(source))&&Path.of(first.get("source_file").getAsString()).equals(input.resolve("plate.tif")),"a source that is an unchanged file on disk is identified by path and pixel hash instead of being copied");
        // the image that was measured is often a derived copy that exists nowhere on disk: it must be kept
        var derived=new ImagePlus("cp_work",source.getProcessor().duplicate());derived.getProcessor().set(0,0,77);
        var kept=AnalysisBundle.saveResults(derived,labels,1,snapshot,budget.output.fresh("results"));Path keptDir=Path.of(kept.get("bundle_directory").getAsString());
        var reopened=new ij.io.Opener().openImage(keptDir.resolve("source.tif").toString());
        check(kept.get("source_copied").getAsBoolean()&&reopened!=null&&ImageEvidence.hash(reopened).equals(ImageEvidence.hash(derived))&&!kept.has("source_file"),"a derived source image is saved with the results, pixel for pixel");
        source.changes=true;check(ChatOutput.sourceFile(source)==null,"an edited image no longer counts as the file it was opened from");source.changes=false;
        check(input.resolve("plate.tif").equals(ChatOutput.sourceFile(source)),"an unchanged opened image is its file");
        check(Files.readString(results.resolve("measurements.csv")).startsWith("Label,Area_pixels,Mean_source_intensity")||Files.readString(results.resolve("measurements.csv")).contains("Area_pixels"),"fixed table columns");
        try{AnalysisBundle.saveResults(source,labels,1,snapshot,results);throw new AssertionError("existing result set overwritten");}catch(java.io.IOException expected){}
        var archived=AnalysisBundle.save(source,labels,1,snapshot,root.resolve("archive-mode"));
        check(Files.exists(Path.of(archived.get("bundle_directory").getAsString()).resolve("source.tif"))&&archived.get("source_copied").getAsBoolean(),"outside a chat folder the set stays self-contained with the source TIFF");
        // delivered script: literal folder path is reported, not blocked
        var literal=ScriptTargets.write(new ScriptTargets.Snapshot("fixed.ijm","x",new JsonObject()),ScriptValidation.hash("x"),home.resolve("scripts").resolve("fixed.ijm").toString(),false);
        check(literal.get("status").getAsString().equals("saved"),"script file written into scripts/");
        // delivered macros must not be steered into depending on this extension
        var prepared=WorkflowTools.prepare(dev.langchain4j.model.chat.request.ChatRequest.builder().messages(List.of(dev.langchain4j.data.message.SystemMessage.from("base"),dev.langchain4j.data.message.UserMessage.from("task"))).build());
        String guidance=((dev.langchain4j.data.message.SystemMessage)prepared.messages().get(0)).text();
        check(!guidance.contains("fiji_command_macro_template")&&guidance.contains("do not make it depend on this extension")&&guidance.contains("fiji_output_folder"),"the workflow guidance asks for ordinary macros and names the output folder tool");
        String template=tools.keySet().stream().filter(s->s.name().equals("fiji_command_macro_template")).findFirst().orElseThrow().description();
        check(template.contains("does not run in a Fiji without it"),"the scaffold tool says plainly that its macro needs this extension");
        check(java.util.regex.Pattern.compile("sc\\.fiji\\.llm").matcher(WorkflowTools.macroTemplate("x.Y",new JsonObject(),1)).find(),"the scaffold itself is unchanged for those who ask for it");
        // switched off: the folder tool reports it and creates nothing
        var offBudget=new BoundedContext(24000,1_048_576);offBudget.output.enabled=false;var offTools=WorkflowTools.tools(original,()->null,offBudget);
        Path quiet=Files.createDirectories(root.resolve("tools-off"));var quietImage=opened(quiet,"q.tif",new ByteProcessor(4,4));quietImage.show();ij.WindowManager.setTempCurrentImage(quietImage);
        check(call(offTools,"fiji_output_folder","{\"image_id\":"+quietImage.getID()+"}").get("status").getAsString().equals("switched_off")&&Files.list(quiet).count()==1,"switched off: the tool creates nothing");
        // explicit parent named by the user
        Path elsewhere=Files.createDirectories(root.resolve("user named place"));
        var moved=call(tools,"fiji_output_folder","{\"directory\":"+new Gson().toJson(elsewhere.toString())+"}");
        check(Path.of(moved.get("folder").getAsString()).getParent().equals(elsewhere),"a parent folder named by the user is honoured");
        check(call(tools,"fiji_output_folder","{\"directory\":"+new Gson().toJson(root.resolve("does-not-exist").toString())+"}").get("status").getAsString().equals("unavailable")&&!Files.exists(root.resolve("does-not-exist")),"a missing parent is reported, not created");
        ij.WindowManager.setTempCurrentImage(null);source.changes=false;source.close();quietImage.changes=false;quietImage.close();
    }
}
