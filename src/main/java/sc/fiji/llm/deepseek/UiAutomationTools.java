/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.awt.*;
import java.awt.event.*;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.swing.*;
import com.google.gson.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.data.message.*;
import dev.langchain4j.service.tool.ToolExecutor;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.module.ModuleItem;

/** Public AWT/Swing operations inside Fiji; no OS input or reflective UI access. */
final class UiAutomationTools {
    static final String MARKER="[Fiji automation compatibility]";
    static final Registry UI=new Registry();
    private UiAutomationTools(){}
    static Map<ToolSpecification,ToolExecutor> tools(Map<ToolSpecification,ToolExecutor> original,Supplier<CommandService> commands){
        var result=new LinkedHashMap<>(original);
        add(result,"fiji_ui_windows_identify","List visible Fiji windows with stable window_id, owner/image identity, buttons and numeric control_id values. Use these IDs when titles are ambiguous.",
            JsonObjectSchema.builder().addStringProperty("window_id","Optional ID to inspect one window").build(),a->UI.windows(string(a,"window_id")));
        add(result,"fiji_ui_dialog_respond_by_id","Click one enabled button in a specific live dialog by window_id. Commits numeric editors before affirmative actions; duplicate titles are allowed.",
            JsonObjectSchema.builder().addStringProperty("window_id").addStringProperty("button_text").required("window_id","button_text").build(),a->UI.respond(string(a,"window_id"),string(a,"button_text")));
        add(result,"fiji_ui_number_set_by_id","Set and commit one numeric control, then return its actual model value. Use control_id from fiji_ui_windows_identify, not the text inside an uncommitted spinner editor.",
            JsonObjectSchema.builder().addStringProperty("window_id").addStringProperty("control_id").addStringProperty("value","Numeric value, e.g. 110 or 0.4").required("window_id","control_id","value").build(),a->UI.number(string(a,"window_id"),string(a,"control_id"),string(a,"value")));
        add(result,"fiji_image_close_by_id","Close exactly one ImageJ image by image_id. Repeated requests reuse a pending close and never stack prompts. discard_changes defaults to false; use true only for disposable temporary images you created, never source images or final results.",
            JsonObjectSchema.builder().addIntegerProperty("image_id").addBooleanProperty("discard_changes").required("image_id").build(),a->UI.close(a.get("image_id").getAsBigDecimal().intValueExact(),a.has("discard_changes")&&a.get("discard_changes").getAsBoolean()));
        add(result,"fiji_image_close_status_by_id","Read the actual outcome of a close request: closed, waiting for a dialog, pending, cancelled/not closed, or error. Use returned window_id to answer its dialog instead of repeating close.",
            JsonObjectSchema.builder().addStringProperty("run_id").required("run_id").build(),a->UI.closeStatus(string(a,"run_id")));
        add(result,"fiji_command_parameters","Read real SciJava command input names, labels, types and constraints without running the command. Prefer these names to guessed macro labels. A parameter name alone does not certify a third-party plugin's macro adapter.",
            JsonObjectSchema.builder().addStringProperty("command","Exact command title, class or menu leaf, e.g. Cellpose-SAM...").required("command").build(),a->parameters(commands.get(),string(a,"command")));
        return result;
    }
    interface Operation {JsonObject apply(JsonObject arguments)throws Exception;}
    static void add(Map<ToolSpecification,ToolExecutor> tools,String name,String description,JsonObjectSchema schema,Operation action){
        tools.put(ToolSpecification.builder().name(name).description(description).parameters(schema).build(),(request,memoryId)->{
            try{return action.apply(JsonParser.parseString(request.arguments()).getAsJsonObject()).toString();}
            catch(Exception failure){var error=new JsonObject();error.addProperty("error",String.valueOf(failure.getMessage()));return error.toString();}
        });
    }
    static String string(JsonObject a,String key){return a.has(key)&&!a.get(key).isJsonNull()?a.get(key).getAsString():"";}
    static ChatRequest prepare(ChatRequest request){
        var messages=new ArrayList<>(request.messages());
        if(messages.stream().anyMatch(m->m instanceof SystemMessage s&&s.text().contains(MARKER)))return request;
        String guide=MARKER+" Use stable window/control IDs for dialogs and numeric inputs. Set numbers with fiji_ui_number_set_by_id and inspect the committed value. Use fiji_command_parameters for real input names. Close disposable helper images with fiji_image_close_by_id; reuse/poll a pending close instead of issuing it again. Preserve source images and final results.";
        for(int i=0;i<messages.size();i++)if(messages.get(i) instanceof SystemMessage s){messages.set(i,SystemMessage.from(s.text()+"\n\n"+guide));return request.toBuilder().messages(messages).build();}
        messages.add(0,SystemMessage.from(guide));return request.toBuilder().messages(messages).build();
    }
    static JsonObject parameters(CommandService service,String query){
        if(service==null)throw new IllegalStateException("Command metadata service unavailable");
        var matches=new ArrayList<CommandInfo>();
        for(var info:service.getCommands())if(query.equals(info.getClassName())||query.equals(info.getTitle())||
            (info.getMenuPath()!=null&&info.getMenuPath().getLeaf()!=null&&query.equals(info.getMenuPath().getLeaf().getName())))matches.add(info);
        if(matches.size()!=1)throw new IllegalArgumentException("Expected one command; found "+matches.size()+". Use its exact class name if necessary.");
        var info=matches.get(0);var value=new JsonObject();value.addProperty("command_class",info.getClassName());value.addProperty("title",info.getTitle());var inputs=new JsonArray();
        for(ModuleItem<?> input:info.inputs()){
            if(org.scijava.service.Service.class.isAssignableFrom(input.getType())||input.getVisibility()==org.scijava.ItemVisibility.MESSAGE)continue;
            var field=new JsonObject();field.addProperty("name",input.getName());field.addProperty("label",input.getLabel());field.addProperty("type",input.getType().getTypeName());field.addProperty("required",input.isRequired());
            put(field,"minimum",input.getMinimumValue());put(field,"maximum",input.getMaximumValue());put(field,"step",input.getStepSize());
            if(input.getChoices()!=null&&!input.getChoices().isEmpty()){var choices=new JsonArray();for(Object c:input.getChoices())choices.add(String.valueOf(c));field.add("choices",choices);}inputs.add(field);
        }
        value.add("inputs",inputs);value.addProperty("executed",false);value.addProperty("note","Static parameter metadata; current dialog/persisted values may differ. Use numeric-control readback for current values.");return value;
    }
    static void put(JsonObject object,String name,Object value){if(value==null)return;if(value instanceof Number n){if((n instanceof Double||n instanceof Float)&&!Double.isFinite(n.doubleValue()))object.addProperty(name,n.toString());else object.addProperty(name,n);}else if(value instanceof Boolean b)object.addProperty(name,b);else if(value instanceof String||value instanceof Enum<?>)object.addProperty(name,String.valueOf(value));}
    static <T>T edt(Callable<T> action)throws Exception{
        if(EventQueue.isDispatchThread())return action.call();
        var future=new FutureTask<T>(action);EventQueue.invokeLater(future);
        try{return future.get(10,TimeUnit.SECONDS);}catch(ExecutionException wrapped){if(wrapped.getCause() instanceof Exception e)throw e;throw new RuntimeException(wrapped.getCause());}
    }
    static Number convert(Number original,String value){
        BigDecimal number=new BigDecimal(value);
        if(original instanceof Integer)return number.intValueExact();if(original instanceof Long)return number.longValueExact();if(original instanceof Short)return number.shortValueExact();if(original instanceof Byte)return number.byteValueExact();
        if(original instanceof Float){float f=number.floatValue();if(!Float.isFinite(f))throw new IllegalArgumentException("Non-finite value");return f;}
        if(original instanceof BigDecimal)return number;
        double d=number.doubleValue();if(!Double.isFinite(d))throw new IllegalArgumentException("Non-finite value");return d;
    }
    static void setNumber(Component component,String value)throws Exception{
        if(!component.isEnabled())throw new IllegalStateException("Numeric control is disabled");
        if(component instanceof JSpinner spinner&&spinner.getModel() instanceof SpinnerNumberModel model){
            Number parsed=convert(model.getNumber(),value);
            BigDecimal number=new BigDecimal(parsed.toString());
            if(model.getMinimum() instanceof Number min&&Double.isFinite(min.doubleValue())&&number.compareTo(new BigDecimal(min.toString()))<0 || model.getMaximum() instanceof Number max&&Double.isFinite(max.doubleValue())&&number.compareTo(new BigDecimal(max.toString()))>0)throw new IllegalArgumentException("Value outside spinner range");
            spinner.setValue(parsed);spinner.commitEdit();
            if(!spinner.getValue().equals(parsed))throw new IllegalStateException("Committed value differs from requested value");
        }else if(component instanceof JFormattedTextField field&&field.getValue() instanceof Number old){
            Number parsed=convert(old,value);field.setText(value);field.commitEdit();
            if(!(field.getValue() instanceof Number n)||new BigDecimal(n.toString()).compareTo(new BigDecimal(parsed.toString()))!=0)throw new IllegalStateException("Committed value differs from requested value");
        }else throw new IllegalArgumentException("Control is not a supported numeric widget");
    }
    static final class Registry {
        final AtomicLong sequence=new AtomicLong();
        final Map<Window,String> windows=new WeakHashMap<>();
        final Map<String,WeakReference<Window>> byWindow=new HashMap<>();
        final Map<Component,String> controls=new WeakHashMap<>();
        final Map<String,WeakReference<Component>> byControl=new HashMap<>();
        final Map<Window,String> pendingButtons=new WeakHashMap<>();
        final Map<Integer,Close> closes=new HashMap<>();
        final Map<String,Close> byRun=new HashMap<>();
        String id(Window window){return windows.computeIfAbsent(window,w->{String id="window-"+sequence.incrementAndGet();byWindow.put(id,new WeakReference<>(w));return id;});}
        String controlId(Component component){return controls.computeIfAbsent(component,c->{String id="control-"+sequence.incrementAndGet();byControl.put(id,new WeakReference<>(c));return id;});}
        Window resolve(String key){Window w=byWindow.containsKey(key)?byWindow.get(key).get():null;if(w==null||!w.isShowing())throw new IllegalArgumentException("Window ID is stale or not visible; refresh fiji_ui_windows_identify");return w;}
        JsonObject windows(String only)throws Exception{return edt(()->{var result=new JsonObject();var list=new JsonArray();if(only!=null&&!only.isBlank())list.add(describe(resolve(only)));else for(Window w:Window.getWindows())if(w.isShowing())list.add(describe(w));result.add("windows",list);return result;});}
        static String title(Window w){return w instanceof Frame f?f.getTitle():w instanceof Dialog d?d.getTitle():w.getName();}
        JsonObject describe(Window w){
            var out=new JsonObject();out.addProperty("window_id",id(w));out.addProperty("title",title(w));out.addProperty("class_name",w.getClass().getName());out.addProperty("active",w.isActive());out.addProperty("modal",w instanceof Dialog d&&d.isModal());
            if(w.getOwner()!=null){out.addProperty("owner_window_id",id(w.getOwner()));out.addProperty("owner_title",title(w.getOwner()));}
            if(w instanceof ij.gui.ImageWindow iw&&iw.getImagePlus()!=null)out.addProperty("image_id",iw.getImagePlus().getID());
            if(w.getOwner() instanceof ij.gui.ImageWindow iw&&iw.getImagePlus()!=null)out.addProperty("owner_image_id",iw.getImagePlus().getID());
            var buttons=new JsonArray();var numbers=new JsonArray();var labels=new JsonArray();
            visit(w,c->{
                String text=buttonText(c);
                if(text!=null&&!text.isBlank()){var button=new JsonObject();button.addProperty("text",text);button.addProperty("enabled",c.isEnabled());buttons.add(button);}
                if(c instanceof JLabel l&&labels.size()<40&&l.getText()!=null&&!l.getText().isBlank())labels.add(BoundedContext.excerpt(l.getText().replaceAll("<[^>]*>"," "),300));
                else if(c instanceof Label l&&labels.size()<40&&!l.getText().isBlank())labels.add(l.getText());
                if(c instanceof JSpinner s&&s.getValue() instanceof Number){var n=new JsonObject();n.addProperty("control_id",controlId(c));n.addProperty("label",label(w,c));put(n,"value",s.getValue());if(s.getModel() instanceof SpinnerNumberModel model){put(n,"minimum",model.getMinimum());put(n,"maximum",model.getMaximum());put(n,"step",model.getStepSize());}numbers.add(n);}
                else if(c instanceof JFormattedTextField f&&f.getValue() instanceof Number&&SwingUtilities.getAncestorOfClass(JSpinner.class,f)==null){var n=new JsonObject();n.addProperty("control_id",controlId(c));n.addProperty("label",label(w,c));put(n,"value",f.getValue());numbers.add(n);}
            });out.add("buttons",buttons);out.add("numeric_controls",numbers);out.add("labels",labels);return out;
        }
        static String label(Window window,Component target){
            var found=new String[]{""};visit(window,c->{if(c instanceof JLabel l&&l.getText()!=null&&(l.getLabelFor()==target||(l.getLabelFor()!=null&&SwingUtilities.isDescendingFrom(target,l.getLabelFor()))))found[0]=l.getText();});
            if(!found[0].isBlank())return found[0];
            for(Component current=target;current!=null&&current!=window;current=current.getParent()){
                Container parent=current.getParent();if(parent==null)break;Component previous=null;
                for(Component sibling:parent.getComponents()){if(sibling==current)break;if(sibling instanceof JLabel||sibling instanceof Label)previous=sibling;}
                if(previous instanceof JLabel l)return l.getText();if(previous instanceof Label l)return l.getText();
            }
            return target.getName()==null?"":target.getName();
        }
        JsonObject number(String windowId,String componentId,String value)throws Exception{return edt(()->{
            Window window=resolve(windowId);Component c=byControl.containsKey(componentId)?byControl.get(componentId).get():null;
            if(c==null||!c.isShowing()||SwingUtilities.getWindowAncestor(c)!=window)throw new IllegalArgumentException("Control ID is stale or belongs to a different window");
            setNumber(c,value);return describe(window);
        });}
        JsonObject respond(String windowId,String button)throws Exception{return edt(()->{
            Window window=resolve(windowId);if(!(window instanceof Dialog))throw new IllegalArgumentException("Target is not a dialog");
            if(pendingButtons.containsKey(window))return queued(windowId,pendingButtons.get(window));
            var matches=new ArrayList<Component>();visit(window,c->{String text=buttonText(c);if(text!=null&&text.strip().equals(button.strip())&&c.isEnabled())matches.add(c);});
            if(matches.size()!=1)throw new IllegalArgumentException("Expected one enabled matching button; found "+matches.size());
            if(!button.strip().matches("(?i)cancel|取消|no|don't save|不保存")){
                var edits=new ArrayList<Component>();visit(window,edits::add);
                for(Component c:edits)if(c instanceof JSpinner s)s.commitEdit();else if(c instanceof JFormattedTextField f&&SwingUtilities.getAncestorOfClass(JSpinner.class,f)==null)f.commitEdit();
            }
            Component target=matches.get(0);
            // Dispatch later: Save/Run may open another modal or start work.
            pendingButtons.put(window,button);
            EventQueue.invokeLater(()->{try{if(!window.isShowing()||!target.isShowing()||!target.isEnabled())return;if(target instanceof AbstractButton b)b.doClick();else if(target instanceof Button b)for(ActionListener listener:b.getActionListeners())listener.actionPerformed(new ActionEvent(b,ActionEvent.ACTION_PERFORMED,b.getActionCommand()));}finally{pendingButtons.remove(window);}});
            return queued(windowId,button);
        });}
        static JsonObject queued(String windowId,String button){var result=new JsonObject();result.addProperty("window_id",windowId);result.addProperty("button_text",button);result.addProperty("status","action_queued");return result;}
        JsonObject close(int imageId,boolean discard)throws Exception{return edt(()->{
            Close existing=closes.get(imageId);if(existing!=null&&!existing.future.isDone())return status(existing);
            ij.ImagePlus image=ij.WindowManager.getImage(imageId);
            if(image==null){if(existing!=null)return status(existing);throw new IllegalArgumentException("Image not found");}
            Window window=image.getWindow();if(window==null)throw new IllegalArgumentException("Image has no visible window");
            Close action=new Close("close-"+sequence.incrementAndGet(),imageId,window);
            // ImageWindow.windowClosing starts an asynchronous, current-image command.
            // Call this specific window's synchronous close on a worker instead, as
            // ImageJ's own command runner does; the future spans its save prompt.
            action.future=new FutureTask<>(()->{if(discard)edt(()->{image.changes=false;return null;});((ij.gui.ImageWindow)window).close();return null;});
            closes.put(imageId,action);byRun.put(action.runId,action);
            Thread worker=new Thread(action.future,"Fiji-image-close-"+action.runId);worker.setDaemon(true);worker.start();return status(action);
        });}
        JsonObject closeStatus(String runId)throws Exception{return edt(()->{Close action=byRun.get(runId);if(action==null)throw new IllegalArgumentException("Unknown close run ID");return status(action);});}
        JsonObject status(Close action){
            var result=new JsonObject();result.addProperty("run_id",action.runId);result.addProperty("image_id",action.imageId);Window window=action.window.get();
            if(window==null||!window.isShowing()){result.addProperty("status","closed");return result;}
            var dialogs=new JsonArray();for(Window child:Window.getWindows())if(child instanceof Dialog&&child.isShowing()&&ownedBy(child,window))dialogs.add(describe(child));
            if(!dialogs.isEmpty()){result.addProperty("status","blocked_by_dialog");result.add("dialogs",dialogs);}
            else if(!action.future.isDone())result.addProperty("status","pending");
            else {try{action.future.get();result.addProperty("status","not_closed");}catch(Exception failure){result.addProperty("status","error");result.addProperty("error",String.valueOf(failure.getCause()==null?failure:failure.getCause()));}}
            return result;
        }
        static boolean ownedBy(Window child,Window owner){for(Window w=child.getOwner();w!=null;w=w.getOwner())if(w==owner)return true;return false;}
        static String buttonText(Component c){return c instanceof AbstractButton b?b.getText():c instanceof Button b?b.getLabel():null;}
        static void visit(Component c,java.util.function.Consumer<Component> consumer){if(!c.isShowing())return;consumer.accept(c);if(c instanceof Container parent)for(Component child:parent.getComponents())visit(child,consumer);}
        static final class Close {final String runId;final int imageId;final WeakReference<Window> window;FutureTask<Void> future;Close(String id,int image,Window w){runId=id;imageId=image;window=new WeakReference<>(w);}}
    }
}
