/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.util.*;
import java.util.function.Consumer;
import com.google.gson.*;
import dev.langchain4j.service.tool.ToolExecution;

/** Per-request display transcript. Reasoning and raw tools never enter this channel. */
final class StagePresentation {
    private final Consumer<String> output;
    private final long started=System.nanoTime();
    private final StringBuilder pending=new StringBuilder();
    private final Map<String,String> runStates=new HashMap<>();
    private String lastStage="";
    private boolean progressHeading;
    private boolean closed;
    private long lastEdit;

    StagePresentation(Consumer<String> output){this.output=output;}
    synchronized void partial(String text){if(!closed && text!=null)pending.append(text);}
    synchronized void intermediate(String completeText){
        if(closed)return;
        String text=completeText==null || completeText.isBlank()?pending.toString():completeText;
        pending.setLength(0);stage(text);
    }
    synchronized void stage(String text){
        if(closed || text==null || text.isBlank())return;
        text=text.strip();if(text.equals(lastStage))return;lastStage=text;
        if(!progressHeading){output.accept("## 阶段进展 / Progress\n\n");progressHeading=true;}
        long seconds=Math.max(0,(System.nanoTime()-started)/1_000_000_000L);
        output.accept("**[+"+seconds+"s]** "+text+"\n\n");
    }
    synchronized void finish(String text,boolean paused){
        if(closed)return;
        if(paused && !pending.isEmpty())stage("**未完成的正文 / Partial response**\n\n"+pending);
        String finalText=text==null || text.isBlank()?pending.toString():text;
        pending.setLength(0);closed=true;
        output.accept((progressHeading?"---\n\n":"")+
            (paused?"## 任务暂停 / Task paused\n\n":"## 最终报告 / Final report\n\n")+finalText);
    }
    synchronized void tool(ToolExecution execution){
        if(closed)return;
        String name=execution.request().name();
        if(name.equals(StageReporting.NAME))return; // the tool already emitted its message
        if(!name.matches("fiji_(macro|script|command)_run(_status)?|fiji_script_(create|replace_content)"))return;
        JsonObject value;
        try {value=JsonParser.parseString(execution.result()).getAsJsonObject();}
        catch(RuntimeException ignored){return;} // raw diagnostics stay in the activity log
        JsonElement error=value.get("error");
        boolean contentError=error!=null&&!error.isJsonNull()&&
            !(error.isJsonPrimitive()&&error.getAsJsonPrimitive().isBoolean()&&!error.getAsBoolean());
        if(execution.hasFailed()||contentError){
            stage("工具报告错误 / Tool reported an error: `"+name+"`。详情见运行日志 / See the activity log.");
            return;
        }
        if(name.matches("fiji_script_(create|replace_content)")){
            long now=System.nanoTime();
            if(lastEdit==0 || now-lastEdit>=30_000_000_000L){
                lastEdit=now;stage("脚本已"+(name.endsWith("create")?"创建":"更新")+" / Script "+
                    (name.endsWith("create")?"created":"updated")+"。详细内容见脚本编辑器 / See the Script Editor.");
            }
            return;
        }
        String status=string(value,"status"),id=string(value,"run_id");
        if(status.isBlank())return;
        String key=id.isBlank()?name:id;
        if(status.equals(runStates.put(key,status)))return;
        String description=switch(status){
            case "running" -> "任务正在执行 / Execution running";
            case "success" -> "执行已结束 / Execution finished: success";
            case "finished_with_warnings" -> "执行结束，有提示 / Execution finished with warnings";
            case "finished_with_errors","infrastructure_error" -> "执行出现错误 / Execution reported an error";
            case "blocked_by_dialog" -> "执行正等待对话框处理 / Execution waiting for a dialog";
            default -> "";
        };
        if(!description.isBlank())stage(description+(id.isBlank()?"":" (`"+id.replace("`","")+"`)")+"。");
    }
    private static String string(JsonObject object,String name){
        return object.has(name)&&object.get(name).isJsonPrimitive()?object.get(name).getAsString():"";
    }
}
