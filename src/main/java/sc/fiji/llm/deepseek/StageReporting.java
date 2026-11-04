/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.util.*;
import com.google.gson.JsonParser;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.data.message.*;
import dev.langchain4j.service.tool.ToolExecutor;

/** An assistant-local reporting tool; no global listener or additional model call. */
final class StageReporting {
    static final String NAME="fiji_report_progress";
    static final String MARKER="[Fiji LLM Provider presentation protocol]";
    static final String INSTRUCTIONS=MARKER+"\n"+
        "Use fiji_report_progress to publish concise user-facing updates after meaningful milestones, "+
        "when changing approach, and before a lengthy execution or wait. Briefly state what has been done "+
        "and what comes next, in the user's language. This is an informational update, not task completion. "+
        "Continue the task after reporting. Keep internal reasoning and verbose debugging in the activity log; "+
        "do not copy them into this tool. End with a self-contained final report. Do not add presentation headings; "+
        "the extension supplies Progress and Final report headings.";
    static Map<ToolSpecification,ToolExecutor> tools(Map<ToolSpecification,ToolExecutor> existing,BoundedContext context){
        var result=new LinkedHashMap<>(existing);
        var specification=ToolSpecification.builder().name(NAME)
            .description("Publish a brief progress update in the chat body while continuing the current task. Use for meaningful milestones, not internal reasoning or raw tool output.")
            .parameters(JsonObjectSchema.builder().addStringProperty("message","Concise user-facing progress and next step, in the user's language; maximum 2000 characters.").required("message").build()).build();
        result.put(specification,(request,memoryId)->{
            var value=JsonParser.parseString(request.arguments()).getAsJsonObject();
            if(!value.has("message") || !value.get("message").isJsonPrimitive() || !value.get("message").getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("message must be a string");
            String message=value.get("message").getAsString().strip();
            if(message.isBlank() || message.length()>2000)throw new IllegalArgumentException("message must contain 1 to 2000 characters");
            context.stageOutput.accept(message);
            return "{\"reported\":true,\"continue_task\":true}";
        });
        return result;
    }
    static ChatRequest prepare(ChatRequest request){
        var messages=new ArrayList<>(request.messages());
        if(messages.stream().anyMatch(m->m instanceof SystemMessage s && s.text().contains(MARKER)))return request;
        for(int i=0;i<messages.size();i++)if(messages.get(i) instanceof SystemMessage system){
            messages.set(i,SystemMessage.from(system.text()+"\n\n"+INSTRUCTIONS));
            return request.toBuilder().messages(messages).build();
        }
        messages.add(0,SystemMessage.from(INSTRUCTIONS));
        return request.toBuilder().messages(messages).build();
    }
}
