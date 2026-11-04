/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
import org.scijava.Context;
import org.scijava.Initializable;
import org.scijava.command.*;
import org.scijava.module.ModuleItem;
import org.scijava.module.ModuleService;
import org.scijava.ItemVisibility;

/** Explicit command inputs, no input harvester or macro-option case conversion.
 * Public run is callable from ImageJ macros via call(class.method, command, JSON, imageID). */
public final class CommandBridge {
    private CommandBridge(){}
    static volatile WeakReference<Context> registered=new WeakReference<>(null);
    static final InheritableThreadLocal<Context> current=new InheritableThreadLocal<>();
    static final ThreadLocal<JsonObject> lastReceipt=new ThreadLocal<>();
    static void register(Context c){registered=new WeakReference<>(c);}
    static void withContext(Context c,Runnable action){Context previous=current.get();current.set(c);try{action.run();}finally{if(previous==null)current.remove();else current.set(previous);}}
    public static String run(String command,String inputsJson,String imageId){
        Context c=current.get();if(c==null)c=registered.get();if(c==null)throw new IllegalStateException("Fiji LLM Provider command context unavailable");
        try{return execute(c,command,JsonParser.parseString(inputsJson).getAsJsonObject(),Integer.parseInt(imageId)).toString();}
        catch(Exception e){throw new IllegalStateException("Command failed: "+e.getMessage(),e);}
    }
    public static String lastOutputImage(){
        JsonObject receipt=lastReceipt.get();if(receipt==null)throw new IllegalStateException("No command receipt on this macro thread");
        var images=receipt.getAsJsonArray("new_images_observed");if(images.size()!=1)throw new IllegalStateException("Expected exactly one newly observed image; inspect command receipt instead of guessing an image");
        return images.get(0).getAsJsonObject().get("image_id").getAsString();
    }
    static CommandInfo find(Context c,String query){
        var matches=new ArrayList<CommandInfo>();for(var info:c.service(CommandService.class).getCommands())
            if(query.equals(info.getClassName())||query.equals(info.getTitle())||info.getMenuPath()!=null&&info.getMenuPath().getLeaf()!=null&&query.equals(info.getMenuPath().getLeaf().getName()))matches.add(info);
        if(matches.size()!=1)throw new IllegalArgumentException("Expected one command, found "+matches.size()+"; use its exact class name from fiji_command_parameters");return matches.get(0);
    }
    static boolean injectable(Class<?> type){return type==Context.class||org.scijava.service.Service.class.isAssignableFrom(type);}
    static Object convert(JsonElement v,Class<?> type){
        if(v.isJsonNull()){if(type.isPrimitive())throw new IllegalArgumentException("Primitive input cannot be null");return null;}
        if(type==String.class){if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new IllegalArgumentException("String input required");return v.getAsString();}
        if(type==boolean.class||type==Boolean.class){if(!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Boolean input required");return v.getAsBoolean();}
        if(type==int.class||type==Integer.class)return v.getAsBigDecimal().intValueExact();
        if(type==long.class||type==Long.class)return v.getAsBigDecimal().longValueExact();
        if(type==double.class||type==Double.class){double d=v.getAsDouble();if(!Double.isFinite(d))throw new IllegalArgumentException("Finite numeric input required");return d;}
        if(type==float.class||type==Float.class){float d=v.getAsFloat();if(!Float.isFinite(d))throw new IllegalArgumentException("Finite numeric input required");return d;}
        if(type.isEnum()){for(Object e:type.getEnumConstants())if(((Enum<?>)e).name().equals(v.getAsString())||e.toString().equals(v.getAsString()))return e;throw new IllegalArgumentException("Unknown enum choice: "+v);}
        throw new IllegalArgumentException("Unsupported input type "+type.getName()+"; use the original command UI");
    }
    static org.scijava.module.Module prepare(Context c,CommandInfo info,JsonObject values,ij.ImagePlus image)throws Exception{
        var module=c.service(ModuleService.class).createModule(info);if(module==null)throw new IllegalStateException("Module creation returned null");
        if(module instanceof org.scijava.Contextual contextual&&contextual.getContext()==null)contextual.setContext(c);
        Object delegate=module.getDelegateObject();if(delegate!=module&&delegate instanceof org.scijava.Contextual contextual&&contextual.getContext()==null)contextual.setContext(c);
        // ModuleService already injects the module/delegate; reinjection is rejected
        // by Context for DynamicCommand and can repeat initializer side effects.
        if(delegate instanceof Initializable initializable)initializable.initialize();else module.initialize();
        var fields=new LinkedHashMap<String,ModuleItem<?>>();for(var f:module.getInfo().inputs())if(!injectable(f.getType())&&f.getVisibility()!=ItemVisibility.MESSAGE)fields.put(f.getName(),f);
        for(var entry:values.entrySet()){
            var f=fields.get(entry.getKey());if(f==null)throw new IllegalArgumentException("Unknown/noneditable input: "+entry.getKey()+". Names are case-sensitive.");
            Object value=convert(entry.getValue(),f.getType());
            if(value instanceof Number n){if(f.getMinimumValue() instanceof Number min&&n.doubleValue()<min.doubleValue()||f.getMaximumValue() instanceof Number max&&n.doubleValue()>max.doubleValue())throw new IllegalArgumentException("Out-of-range input "+f.getName());}
            // Dynamic choices sometimes exclude valid explicit values until a callback runs.
            // Preserve real typed inputs; the command remains responsible for its domain validation.
            module.setInput(f.getName(),value);module.resolveInput(f.getName());
        }
        if(fields.containsKey("image_title")&&image!=null){
            if(values.has("image_title")&&!image.getTitle().equals(values.get("image_title").getAsString()))throw new IllegalArgumentException("image_title must match the selected image_id");
            if(ij.WindowManager.getImage(image.getTitle())!=image)throw new IllegalArgumentException("Input image title is ambiguous; use a uniquely named channel copy");
            module.setInput("image_title",image.getTitle());module.resolveInput("image_title");
        }
        for(var f:fields.values())if(f.isRequired()&&module.getInput(f.getName())==null)throw new IllegalArgumentException("Missing required input: "+f.getName());
        return module;
    }
    static JsonObject execute(Context c,String query,JsonObject values,int imageId)throws Exception{
        lastReceipt.remove();
        if(javax.swing.SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Run commands from a worker, not the UI thread");
        var image=ij.WindowManager.getImage(imageId);if(image==null)throw new IllegalArgumentException("Input image ID is no longer open");
        ImageEvidence.checkInput(image);String before=ImageEvidence.hash(image);int[] previous=ij.WindowManager.getIDList();Set<Integer> old=new HashSet<>();if(previous!=null)for(int id:previous)old.add(id);
        var info=find(c,query);long start=System.nanoTime();ij.ImagePlus oldTemp=ij.WindowManager.getTempCurrentImage();
        ij.WindowManager.setTempCurrentImage(image);
        try{
            var module=prepare(c,info,values,image);var effective=new JsonObject();
            for(var f:module.getInfo().inputs())if(!injectable(f.getType())&&f.getVisibility()!=ItemVisibility.MESSAGE)UiAutomationTools.put(effective,f.getName(),module.getInput(f.getName()));
            if(module instanceof org.scijava.Cancelable cancel&&cancel.isCanceled())throw new IllegalStateException("Command initialization cancelled: "+cancel.getCancelReason());
            // Invoke the initialized module directly. No input/UI harvesting or persisted-input preprocessing.
            module.run();
            if(module instanceof org.scijava.Cancelable cancel&&cancel.isCanceled())throw new IllegalStateException("Command cancelled: "+cancel.getCancelReason());
            var result=new JsonObject();result.addProperty("status","success");result.addProperty("command_class",info.getClassName());result.add("effective_inputs",effective);result.addProperty("elapsed_ms",(System.nanoTime()-start)/1_000_000);
            result.addProperty("input_image_id",imageId);result.addProperty("input_sha256_before",before);result.addProperty("input_sha256_after",ImageEvidence.hash(image));result.addProperty("input_pixels_unchanged",before.equals(result.get("input_sha256_after").getAsString()));
            var added=new JsonArray();int[] ids=ij.WindowManager.getIDList();if(ids!=null)for(int id:ids)if(!old.contains(id)){var im=ij.WindowManager.getImage(id);var row=new JsonObject();row.addProperty("image_id",id);row.addProperty("title",im.getTitle());added.add(row);}result.add("new_images_observed",added);
            result.addProperty("actual_device","not_reported_by_command");result.addProperty("note","Effective GPU/CUDA inputs are configuration, not independent proof of the backend device. Newly observed windows may include concurrent external activity.");lastReceipt.set(result.deepCopy());return result;
        }finally{ij.WindowManager.setTempCurrentImage(oldTemp);}
    }
}
