/* SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu. See LICENSE.txt and NOTICE.txt. */
package sc.fiji.llm.deepseek;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.google.gson.JsonObject;
import sc.fiji.llm.ui.TextEditorUtils;
import sc.fiji.llm.script.*;

/** Session evidence, not a claim of scientific correctness or clean-state replay. */
final class ScriptValidation {
    record Run(String hash,String runId,String status){}
    static final Map<String,Run> latest=new ConcurrentHashMap<>();
    static String hash(String source){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static void record(String id,String source,String run,String status){latest.put(id,new Run(hash(source),run,status));}
    static JsonObject evidence(String id,String source){
        return evidence(id,source,true);
    }
    static JsonObject evidence(String id,String source,boolean macro){
        var out=new JsonObject();String current=hash(source);Run r=latest.get(id);
        out.addProperty("script_id",id);out.addProperty("current_sha256",current);
        out.addProperty("execution_evidence_supported",macro);
        if(!macro){
            out.addProperty("status","not_monitored");out.addProperty("current_version_executed_successfully",false);
            out.addProperty("clean_state_replay_verified",false);
            out.addProperty("note","Exact-byte execution evidence is recorded for macros only. Ordinary scripts use Fiji Chat's original execution service; consult that run's output/status. No execution claim is made by this checker.");return out;
        }
        boolean match=r!=null&&r.hash.equals(current);boolean success=match&&(r.status.equals("success")||r.status.equals("finished_with_warnings"));
        out.addProperty("status",r==null?"not_run_in_this_session":!match?"modified_since_last_run":success?"executed_current_version":"current_version_failed");
        out.addProperty("current_version_executed_successfully",success);
        if(r!=null){out.addProperty("last_run_id",r.runId);out.addProperty("last_run_sha256",r.hash);out.addProperty("last_run_status",r.status);}
        out.addProperty("clean_state_replay_verified",false);
        out.addProperty("note","Execution of these exact script bytes in the recorded session is distinct from clean-state reproducibility and scientific correctness.");return out;
    }
    static JsonObject active()throws Exception{return UiAutomationTools.edt(()->{
        ScriptID id=TextEditorUtils.getActiveScriptID();if(id==null)throw new IllegalStateException("No active script");
        var item=ScriptContextUtilities.buildScriptContextItem(id.editorIndex,id.tabIndex);
        var out=evidence(id.toString(),item.getScriptBody(),ScriptExecutionService.isMacroScript(item.getScriptName()));out.addProperty("script_name",item.getScriptName());return out;
    });}
}
