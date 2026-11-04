/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import com.google.gson.*;
import ij.ImagePlus;
import ij.process.*;

/** Explicit 2D source/label binding. No global Results or ROI Manager mutation. */
final class ImageEvidence {
    static final String ROLE="FijiLLM.role",HASH="FijiLLM.extracted_sha256";
    static void plane(ImagePlus image){if(image==null)throw new IllegalArgumentException("Image not found");if(image.getStackSize()!=1)throw new IllegalArgumentException("This helper supports one 2D plane; select/extract the intended plane first");}
    static String hash(ImagePlus im){
        try{
            MessageDigest d=MessageDigest.getInstance("SHA-256");ByteBuffer buffer=ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
            for(int value:new int[]{im.getWidth(),im.getHeight(),im.getStackSize(),im.getBitDepth()})buffer.putInt(value);
            for(int s=1;s<=im.getStackSize();s++){
                Object p=im.getStack().getPixels(s);
                if(p instanceof byte[] a){d.update(buffer.array(),0,buffer.position());buffer.clear();d.update(a);continue;}
                int n=p instanceof short[] a?a.length:p instanceof int[] a?a.length:((float[])p).length;
                for(int i=0;i<n;i++){if(buffer.remaining()<4){d.update(buffer.array(),0,buffer.position());buffer.clear();}buffer.putInt(p instanceof short[] a?a[i]&65535:p instanceof int[] a?a[i]:Float.floatToIntBits(((float[])p)[i]));}
            }
            d.update(buffer.array(),0,buffer.position());return HexFormat.of().formatHex(d.digest());
        }catch(Exception e){throw new IllegalStateException("Cannot hash image pixels",e);}
    }
    static void checkInput(ImagePlus image){
        if("qc_display".equals(image.getProperty(ROLE)))throw new IllegalArgumentException("QC display is not an analysis source; use the original image or extract a fresh channel");
        Object expected=image.getProperty(HASH);if(expected!=null&&!expected.equals(hash(image)))throw new IllegalArgumentException("Extracted channel pixels changed after extraction; use a fresh channel from the original source");
    }
    static ImagePlus extract(ImagePlus source,int channel){
        plane(source);if(source.getBitDepth()==24){if(channel<1||channel>3)throw new IllegalArgumentException("RGB channel must be 1=red, 2=green or 3=blue");}
        else if(channel!=1)throw new IllegalArgumentException("Single-channel source requires channel 1");
        ImageProcessor processor=source.getBitDepth()==24?((ColorProcessor)source.getProcessor()).getChannel(channel,null):source.getProcessor().duplicate();
        ImagePlus result=new ImagePlus("Analysis_channel_"+source.getID()+"_"+channel+"_"+UUID.randomUUID().toString().substring(0,6),processor);
        result.setCalibration(source.getCalibration().copy());result.setProperty(ROLE,"analysis_channel");result.setProperty("FijiLLM.source_image_id",source.getID());result.setProperty("FijiLLM.source_sha256",hash(source));result.setProperty(HASH,hash(result));return result;
    }
    static final class ObjectStats {
        final int label;long area;double signal,x,y;boolean border;
        ObjectStats(int l){label=l;}
    }
    static final class Measurement {
        final JsonObject summary;final ij.measure.ResultsTable table;final ImagePlus overlay;
        Measurement(JsonObject s,ij.measure.ResultsTable t,ImagePlus o){summary=s;table=t;overlay=o;}
    }
    static Measurement measure(ImagePlus source,ImagePlus labels,int channel){
        plane(source);plane(labels);checkInput(source);
        if(source==labels)throw new IllegalArgumentException("Intensity source and label image must be different images");
        if(source.getWidth()!=labels.getWidth()||source.getHeight()!=labels.getHeight())throw new IllegalArgumentException("Source and labels must have identical dimensions");
        if(labels.getBitDepth()!=16&&labels.getBitDepth()!=32)throw new IllegalArgumentException("Labels must be a 16-bit or integer-valued 32-bit image");
        if(source.getBitDepth()==24?(channel<1||channel>3):channel!=1)throw new IllegalArgumentException("Choose the explicit source channel (RGB: 1/2/3; grayscale: 1)");
        String sourceHash=hash(source),labelHash=hash(labels);int width=labels.getWidth(),height=labels.getHeight();var objects=new TreeMap<Integer,ObjectStats>();double allSignal=0,insideSignal=0;boolean nonnegative=true;
        ImageProcessor lp=labels.getProcessor(),sp=source.getProcessor();int[] edges=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int pos=y*width+x;float raw=lp.getf(pos);if(!Float.isFinite(raw)||raw<0||raw!=Math.rint(raw)||raw>Integer.MAX_VALUE)throw new IllegalArgumentException("Label values must be finite nonnegative integers");int label=(int)raw;
            double intensity=source.getBitDepth()==24?(sp.get(pos)>>(8*(3-channel)))&255:sp.getf(pos);
            if(!Double.isFinite(intensity))throw new IllegalArgumentException("Intensity source contains non-finite values");allSignal+=intensity;if(intensity<0)nonnegative=false;
            if(label==0)continue;insideSignal+=intensity;var object=objects.computeIfAbsent(label,ObjectStats::new);object.area++;object.signal+=intensity;object.x+=x;object.y+=y;
            boolean border=x==0||y==0||x==width-1||y==height-1;object.border|=border;
            if(border||lp.getf(pos-1)!=label||lp.getf(pos+1)!=label||lp.getf(pos-width)!=label||lp.getf(pos+width)!=label)edges[pos]=0xffff00;
        }
        var table=new ij.measure.ResultsTable();long totalArea=0;double[] areas=new double[objects.size()];int i=0,borderCount=0;
        for(var o:objects.values()){
            int row=i;areas[i++]=o.area;totalArea+=o.area;if(o.border)borderCount++;
            table.setValue("Label",row,o.label);table.setValue("Area_pixels",row,o.area);table.setValue("Mean_source_intensity",row,o.signal/o.area);table.setValue("Centroid_x_pixels",row,o.x/o.area);table.setValue("Centroid_y_pixels",row,o.y/o.area);table.setValue("Touches_border",row,o.border?1:0);
        }
        Arrays.sort(areas);var summary=new JsonObject();summary.addProperty("source_image_id",source.getID());summary.addProperty("label_image_id",labels.getID());summary.addProperty("source_channel",channel);summary.addProperty("source_sha256",sourceHash);summary.addProperty("labels_sha256",labelHash);
        summary.addProperty("object_count",objects.size());summary.addProperty("border_object_count",borderCount);summary.addProperty("area_pixels_sum",totalArea);summary.addProperty("image_area_pixels",(long)width*height);summary.addProperty("mask_area_fraction",totalArea/(double)((long)width*height));
        if(areas.length>0){summary.addProperty("area_pixels_min",areas[0]);summary.addProperty("area_pixels_max",areas[areas.length-1]);summary.addProperty("area_pixels_median",areas.length%2==0?(areas[areas.length/2-1]+areas[areas.length/2])/2:areas[areas.length/2]);}
        if(nonnegative&&allSignal>0)summary.addProperty("source_signal_fraction_inside_labels",insideSignal/allSignal);
        summary.addProperty("count_method","unique nonzero integer labels; no area filter applied");summary.addProperty("intensity_measurement","raw values from the explicitly selected source/channel, not label numbers");summary.addProperty("note","Signal fraction is not object recall. Counts and geometry do not establish biological correctness.");
        ImagePlus qc=new ImagePlus("Label_QC_"+UUID.randomUUID().toString().substring(0,8),sp.duplicate());qc.setCalibration(source.getCalibration().copy());
        var roi=new ij.gui.ImageRoi(0,0,new ColorProcessor(width,height,edges));roi.setZeroTransparent(true);qc.setOverlay(new ij.gui.Overlay(roi));qc.setProperty(ROLE,"qc_display");
        if(!sourceHash.equals(hash(source))||!labelHash.equals(hash(labels)))throw new IllegalStateException("Source or labels changed while measuring");return new Measurement(summary,table,qc);
    }
    static JsonObject deliver(ImagePlus source,ImagePlus labels,int channel,Path directory,boolean show)throws Exception{
        Measurement m=measure(source,labels,channel);Files.createDirectories(directory);Path csv=directory.resolve("label-measurements-"+UUID.randomUUID()+".csv");m.table.saveAs(csv.toString());m.summary.addProperty("csv_file",csv.toString());
        if(show)UiAutomationTools.edt(()->{m.overlay.show();m.table.show("Fiji LLM measurements "+m.overlay.getID());return null;});
        m.summary.addProperty("qc_image_id",m.overlay.getID());m.summary.addProperty("global_results_and_roi_manager_unchanged",true);return m.summary;
    }
}
