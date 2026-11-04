/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. */
package sc.fiji.llm.deepseek;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import com.google.gson.*;
import ij.*;
import ij.process.*;
import ij.io.*;
import sc.fiji.llm.script.*;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

/** Exact ROI/file round trips and real registered script tools. No hosted model. */
public final class DeliveryVerificationMain {
    static{net.imagej.patcher.LegacyInjector.preinit();}
    static void check(boolean yes,String reason){if(!yes)throw new AssertionError(reason);}
    public static void main(String[] args){try{
        if(args.length>0)gui();else bundle();
        System.out.println("PASS: delivery bundle TIFF/CSV/ROI/script/hash round trips, gapped/disconnected/holed/single-pixel labels, unique destinations and no source/global Results mutation");
        Runtime.getRuntime().halt(0);
    }catch(Throwable failure){failure.printStackTrace();Runtime.getRuntime().halt(1);}}
    static ImagePlus[] images(){
        var source=new ImagePlus("original",new ByteProcessor(8,8));var labels=new ImagePlus("labels",new ShortProcessor(8,8));
        for(int y=0;y<8;y++)for(int x=0;x<8;x++){source.getProcessor().set(x,y,10+x+y);if(x>=1&&x<=5&&y>=1&&y<=5&&(x==1||x==5||y==1||y==5))labels.getProcessor().set(x,y,2);}
        labels.getProcessor().set(0,0,9);labels.getProcessor().set(7,7,9);labels.getProcessor().set(6,1,50);return new ImagePlus[]{source,labels};
    }
    static JsonObject bundle()throws Exception{
        var images=images();var source=images[0];var labels=images[1];source.setRoi(2,2,2,2);
        String sh=ImageEvidence.hash(source),lh=ImageEvidence.hash(labels);var global=ij.measure.ResultsTable.getResultsTable();global.setValue("sentinel",0,777);
        var script=new ScriptTargets.Snapshot("replay.ijm","print(\"REPLAY\");\n",ScriptValidation.evidence("0:0","print(\"REPLAY\");\n"));
        Path root=Path.of(System.getProperty("fiji.llm.contextArchiveRoot")).resolve("bundles");
        var saved=AnalysisBundle.save(source,labels,1,script,root);check(saved.get("status").getAsString().equals("saved")&&saved.get("roi_count").getAsInt()==3,"one ROI per unique label");
        Path directory=Path.of(saved.get("bundle_directory").getAsString());
        var decoded=new HashMap<Integer,ij.gui.Roi>();
        try(var zip=new ZipFile(directory.resolve("rois.zip").toFile())){
            check(zip.size()==3,"ROI ZIP has exactly three entries");
            for(var mapping:saved.getAsJsonArray("roi_labels")){var row=mapping.getAsJsonObject();String name=row.get("zip_entry").getAsString();byte[] bytes=zip.getInputStream(zip.getEntry(name)).readAllBytes();decoded.put(row.get("label").getAsInt(),new RoiDecoder(bytes,name).getRoi());}
        }
        for(int y=0;y<8;y++)for(int x=0;x<8;x++)for(var r:decoded.entrySet())check(r.getValue().contains(x,y)==(labels.getProcessor().get(x,y)==r.getKey()),"ROI raster must exactly match label, including holes and disconnected parts");
        var opener=new Opener();var restoredSource=opener.openImage(directory.resolve("source.tif").toString());var restoredLabels=opener.openImage(directory.resolve("labels.tif").toString());
        check(ImageEvidence.hash(restoredSource).equals(sh)&&ImageEvidence.hash(restoredLabels).equals(lh),"TIFF raw pixels round trip without ROI crop");
        check(Files.readString(directory.resolve("script/replay.ijm")).equals(script.content()),"saved script exact bytes");
        for(var e:saved.getAsJsonArray("files")){var f=e.getAsJsonObject();Path p=Path.of(f.get("path").getAsString());check(Files.size(p)==f.get("bytes").getAsLong()&&AnalysisBundle.fileHash(p).equals(f.get("sha256").getAsString()),"artifact size/hash exact");}
        check(AnalysisBundle.fileHash(Path.of(saved.get("manifest_file").getAsString())).equals(saved.get("manifest_sha256").getAsString()),"manifest hash exact");
        var again=AnalysisBundle.save(source,labels,1,script,root);check(!again.get("bundle_directory").equals(saved.get("bundle_directory")),"exports never overwrite prior bundle");
        var zero=AnalysisBundle.save(source,new ImagePlus("empty",new ShortProcessor(8,8)),1,new ScriptTargets.Snapshot("empty.ijm","",new JsonObject()),root);
        check(zero.get("roi_count").getAsInt()==0&&Files.readString(Path.of(zero.get("bundle_directory").getAsString()).resolve("measurements.csv")).startsWith("Label,"),"empty labels and script are valid explicit artifacts");
        Path blocked=root.resolve("existing-file");Files.writeString(blocked,"PRESERVE");try{AnalysisBundle.save(source,labels,1,script,blocked);throw new AssertionError("invalid root accepted");}catch(java.io.IOException expected){}
        check(Files.readString(blocked).equals("PRESERVE"),"invalid export never replaces existing file");
        check(sh.equals(ImageEvidence.hash(source))&&lh.equals(ImageEvidence.hash(labels))&&source.getRoi().getBounds().width==2&&global.getValue("sentinel",0)==777,"source, selection and global Results retained");return saved;
    }
    static JsonObject call(Map<ToolSpecification,ToolExecutor> tools,String name,String arguments){
        var entry=tools.entrySet().stream().filter(e->e.getKey().name().equals(name)).toList();check(entry.size()==1,"one specification per tool name");
        return JsonParser.parseString(entry.get(0).getValue().execute(ToolExecutionRequest.builder().id(UUID.randomUUID().toString()).name(name).arguments(arguments).build(),null)).getAsJsonObject();
    }
    static void gui()throws Exception{
        var context=MacroExecutionVerificationMain.fixtureContext();var budget=new BoundedContext(24000,1_048_576);
        var original=new LinkedHashMap<ToolSpecification,ToolExecutor>();for(String n:List.of("fiji_script_create","fiji_script_replace_content"))original.put(ToolSpecification.builder().name(n).description("old fixture").parameters(JsonObjectSchema.builder().build()).build(),(a,b)->{throw new AssertionError("old active-tab executor invoked");});
        var tools=WorkflowTools.tools(original,()->context,budget);
        var created=call(tools,"fiji_script_create","{\"script_name\":\"delivered.ijm\",\"language\":\"ImageJ Macro\",\"content\":\"print(\\\"FIRST\\\");\"}");check(!created.has("error"),"explicit macro creation: "+created);
        String handle=created.get("script_handle").getAsString(),hash=created.get("current_sha256").getAsString(),id=created.get("script_id").getAsString();
        var other=call(tools,"fiji_script_create","{\"script_name\":\"cleanup.groovy\",\"language\":\"Groovy\",\"content\":\"OTHER\"}");check(!other.has("error"),"explicit Groovy creation");
        String content="newImage(\"ID_OUTPUT\",\"8-bit black\",8,8,1); newImage(\"ID_OUTPUT\",\"8-bit black\",8,8,1); print(\"DONE\");";
        var args=new JsonObject();args.addProperty("script_handle",handle);args.addProperty("expected_sha256",hash);args.addProperty("content",content);
        var changed=call(tools,"fiji_script_replace_content",args.toString());check(!changed.has("error")&&changed.getAsJsonObject("script_execution_evidence").get("script_id").getAsString().equals(id),"existing edit tool binds exact target and evidence");
        var inactive=sc.fiji.llm.ui.TextEditorUtils.getActiveScriptID();check(inactive.toString().equals(other.get("script_id").getAsString()),"edit does not switch active tab");
        check(call(tools,"fiji_script_replace_content","{\"content\":\"WRONG_TAB\"}").has("error"),"legacy active-tab fallback rejected");
        var active=ScriptTargets.read(other.get("script_id").getAsString());check(active.get("content").getAsString().equals("OTHER"),"other tab not changed");
        check(call(tools,"fiji_script_create","{\"script_name\":\"bad.ijm\",\"language\":\"Groovy\"}").has("error"),"name/language mismatch rejected");
        UiAutomationTools.edt(()->{new ScriptEditorToolPlugin().setActiveScript(id);return null;});String[] parts=id.split(":");
        var service=context.service(ScriptExecutionService.class);var receipt=service.run(new ScriptID(Integer.parseInt(parts[0]),Integer.parseInt(parts[1])),ScriptExecutionService.RunKind.MACRO,true).toJson();
        check(receipt.get("status").getAsString().equals("success"),"newly created named macro runs immediately: "+receipt);
        var opened=receipt.getAsJsonArray("new_images_observed");check(opened.size()==2,"macro receipt returns both new output IDs");
        var ids=new HashSet<Integer>();for(var e:opened){int imageId=e.getAsJsonObject().get("image_id").getAsInt();check(ids.add(imageId)&&WindowManager.getImage(imageId)!=null,"distinct resolvable IDs, no title inference");}
        var manager=UiAutomationTools.edt(()->{var rm=ij.plugin.frame.RoiManager.getRoiManager();rm.setLocation(-20000,-20000);rm.addRoi(new ij.gui.Roi(0,0,1,1));return rm;});int count=manager.getCount();bundle();check(manager.getCount()==count,"existing ROI Manager retained by export");
        var synthetic=images();UiAutomationTools.edt(()->{synthetic[0].show();synthetic[1].show();return null;});
        args=new JsonObject();args.addProperty("source_image_id",synthetic[0].getID());args.addProperty("label_image_id",synthetic[1].getID());args.addProperty("source_channel",1);args.addProperty("script_handle",handle);args.addProperty("expected_sha256",ScriptValidation.hash(content));
        var saved=call(tools,"fiji_analysis_save_bundle",args.toString());check(saved.has("manifest_file")&&saved.getAsJsonObject("script_execution_evidence").get("current_version_executed_successfully").getAsBoolean(),"registered save tool persists exact executed source and artifacts");
        check(manager.getCount()==count,"registered bundle tool does not append or reset ROI Manager");
        System.out.println("PASS: registered create/replace tools bind filename/language/handle/hash; no active-tab fallback; immediate macro execution returns live output IDs; registered export retains existing ROIs and saves manifest");
    }
}
