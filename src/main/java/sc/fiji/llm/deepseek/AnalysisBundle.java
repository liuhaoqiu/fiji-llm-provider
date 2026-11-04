/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;
import java.io.*;
import com.google.gson.*;
import ij.*;
import ij.gui.Roi;
import ij.io.*;
import ij.process.*;
import ij.plugin.filter.ThresholdToSelection;

/** Independent, uniquely named output set. Never clears or appends global ROIs. */
final class AnalysisBundle {
    record LabelRoi(int label,Roi roi){}
    static List<LabelRoi> rois(ImagePlus labels){
        ImageEvidence.plane(labels);var ip=labels.getProcessor();var boxes=new TreeMap<Integer,int[]>();
        for(int y=0;y<ip.getHeight();y++)for(int x=0;x<ip.getWidth();x++){
            float value=ip.getf(x,y);if(!Float.isFinite(value)||value<0||value!=Math.rint(value)||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Invalid integer label image");
            int label=(int)value;if(label==0)continue;var b=boxes.get(label);
            if(b==null)boxes.put(label,new int[]{x,y,x,y});else{b[0]=Math.min(b[0],x);b[1]=Math.min(b[1],y);b[2]=Math.max(b[2],x);b[3]=Math.max(b[3],y);}
        }
        var result=new ArrayList<LabelRoi>();
        for(var entry:boxes.entrySet()){
            int label=entry.getKey();int[] b=entry.getValue();var mask=new ByteProcessor(b[2]-b[0]+1,b[3]-b[1]+1);
            for(int y=b[1];y<=b[3];y++)for(int x=b[0];x<=b[2];x++)if(ip.getf(x,y)==label)mask.set(x-b[0],y-b[1],255);
            mask.setThreshold(255,255,ImageProcessor.NO_LUT_UPDATE);Roi roi=new ThresholdToSelection().convert(mask);
            if(roi==null)throw new IllegalStateException("No ROI for label "+label);
            roi.setLocation(b[0],b[1]);roi.setName("label-"+label);result.add(new LabelRoi(label,roi));
        }
        return result;
    }
    static ImagePlus copy(ImagePlus image){var c=new ImagePlus(image.getTitle(),image.getProcessor().duplicate());c.setCalibration(image.getCalibration().copy());return c;}
    static JsonObject save(ImagePlus source,ImagePlus labels,int channel,ScriptTargets.Snapshot script,Path root)throws Exception{
        ImageEvidence.plane(source);ImageEvidence.plane(labels);ImageEvidence.checkInput(source);
        if(source==labels)throw new IllegalArgumentException("Source and label image must differ");
        String sh=ImageEvidence.hash(source),lh=ImageEvidence.hash(labels);
        var sourceCopy=copy(source);var labelCopy=copy(labels);
        if(!sh.equals(ImageEvidence.hash(sourceCopy))||!lh.equals(ImageEvidence.hash(labelCopy))||!sh.equals(ImageEvidence.hash(source))||!lh.equals(ImageEvidence.hash(labels)))
            throw new IllegalStateException("Images changed during snapshot; no bundle saved");
        var measurement=ImageEvidence.measure(sourceCopy,labelCopy,channel);var rois=rois(labelCopy);
        if(rois.size()!=measurement.summary.get("object_count").getAsInt())throw new IllegalStateException("ROI/label count mismatch");
        Files.createDirectories(root);Path directory=Files.createTempDirectory(root,"analysis-").toAbsolutePath();
        try{
            String filename=Path.of(script.name()).getFileName().toString();if(filename.equals(".")||filename.equals("..")||filename.isBlank()||filename.matches(".*[\\\\/:*?\"<>|].*"))filename="script.txt";
            Files.createDirectory(directory.resolve("script"));filename="script/"+filename;
            Files.writeString(directory.resolve(filename),script.content(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            if(!new FileSaver(sourceCopy).saveAsTiff(directory.resolve("source.tif").toString())||!new FileSaver(labelCopy).saveAsTiff(directory.resolve("labels.tif").toString()))throw new IOException("TIFF write failed");
            if(!new FileSaver(measurement.overlay.flatten()).saveAsTiff(directory.resolve("qc.tif").toString()))throw new IOException("QC write failed");
            if(rois.isEmpty())Files.writeString(directory.resolve("measurements.csv"),"Label,Area_pixels,Mean_source_intensity,Centroid_x_pixels,Centroid_y_pixels,Touches_border\n",StandardCharsets.UTF_8);else measurement.table.saveAs(directory.resolve("measurements.csv").toString());var mapping=new JsonArray();
            try(var zip=new ZipOutputStream(Files.newOutputStream(directory.resolve("rois.zip"),StandardOpenOption.CREATE_NEW))){
                for(var r:rois){String entry=r.roi.getName()+".roi";zip.putNextEntry(new ZipEntry(entry));byte[] bytes=RoiEncoder.saveAsByteArray(r.roi);if(bytes==null)throw new IOException("ROI encode failed");zip.write(bytes);zip.closeEntry();
                    var row=new JsonObject();row.addProperty("label",r.label);row.addProperty("zip_entry",entry);mapping.add(row);}
            }
            var files=new JsonArray();for(String name:List.of(filename,"source.tif","labels.tif","qc.tif","measurements.csv","rois.zip")){
                Path p=directory.resolve(name);if(!Files.isRegularFile(p)||(Files.size(p)==0&&!name.equals(filename)))throw new IOException("Missing saved artifact: "+name);
                var row=new JsonObject();row.addProperty("name",name);row.addProperty("path",p.toString());row.addProperty("bytes",Files.size(p));row.addProperty("sha256",fileHash(p));files.add(row);
            }
            var manifest=new JsonObject();manifest.addProperty("status","saved");manifest.addProperty("format","fiji-llm-analysis-bundle-v1");manifest.addProperty("bundle_directory",directory.toString());manifest.add("files",files);
            measurement.summary.addProperty("source_image_id",source.getID());measurement.summary.addProperty("label_image_id",labels.getID());manifest.add("measurements",measurement.summary);
            manifest.addProperty("roi_count",rois.size());manifest.add("roi_labels",mapping);manifest.addProperty("roi_policy","one ROI per unique nonzero label, including disconnected parts/holes; no size filter; global ROI Manager unchanged");
            manifest.addProperty("script_sha256",ScriptValidation.hash(script.content()));manifest.add("script_execution_evidence",script.evidence());
            manifest.addProperty("script_saved_as_snapshot",true);manifest.addProperty("global_results_and_roi_manager_unchanged",true);
            manifest.addProperty("note","Artifact images are explicit selections; script execution evidence does not certify that it produced those images. Saved artifacts and matching script bytes do not certify clean-state replay or scientific correctness. Source TIFF contains raw source pixels without its display overlays.");
            Path mp=directory.resolve("manifest.json");Files.writeString(mp,new GsonBuilder().setPrettyPrinting().create().toJson(manifest),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            var response=manifest.deepCopy();response.addProperty("manifest_file",mp.toString());response.addProperty("manifest_sha256",fileHash(mp));return response;
        }catch(Exception failure){try{Files.writeString(directory.resolve("INCOMPLETE.txt"),"Export incomplete: "+failure,StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);}catch(IOException ignored){}throw new IOException("Bundle export incomplete at "+directory+": "+failure.getMessage(),failure);}
    }
    static String fileHash(Path p)throws Exception{var digest=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
}
