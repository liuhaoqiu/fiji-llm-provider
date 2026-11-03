/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import com.google.gson.*;

/** Public metadata supplements /models IDs; no credentials are sent to the website. */
final class SiliconFlowModelMetadata {
    private static final long TTL = 24L * 60 * 60 * 1000;
    private static final int MAX_BYTES = 2 * 1048576;
    private final Path cache;
    private final URI endpoint;
    private final Duration timeout;
    private Map<String,Long> windows = Map.of();
    private long fetched, attempted;

    SiliconFlowModelMetadata(Path cache) {
        this(cache, URI.create("https://www.siliconflow.cn/models"), Duration.ofSeconds(5));
    }
    SiliconFlowModelMetadata(Path cache, URI endpoint, Duration timeout) {
        this.cache=cache; this.endpoint=endpoint; this.timeout=timeout;
        try {
            if(cache!=null && Files.isRegularFile(cache) && Files.size(cache)<=MAX_BYTES) {
                var root=JsonParser.parseString(Files.readString(cache)).getAsJsonObject();
                if(endpoint.toString().equals(root.get("source").getAsString())) {
                    var values=new LinkedHashMap<String,Long>();
                    for(var entry:root.getAsJsonObject("windows").entrySet()) {
                        long size=entry.getValue().getAsLong(); if(size>0) values.put(entry.getKey(),size);
                    }
                    windows=Map.copyOf(values); fetched=root.get("fetched_at").getAsLong();
                }
            }
        } catch(Exception ignored) { /* Unknown metadata remains visibly unknown. */ }
    }
    synchronized Long window(String id) { refresh(false); return windows.get(id); }
    synchronized void refresh(boolean force) {
        long now=System.currentTimeMillis();
        if(!force && ((fetched>0 && now-fetched<TTL) || (attempted>0 && now-attempted<60000))) return;
        attempted=now;
        try {
            var client=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NORMAL).build();
            var response=client.send(HttpRequest.newBuilder(endpoint).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(response.statusCode()!=200 || response.body().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) return;
            var parsed=parse(response.body());
            if(parsed.isEmpty()) return;
            windows=Map.copyOf(parsed); fetched=now;
            if(cache!=null) {
                var root=new JsonObject();root.addProperty("source",endpoint.toString());root.addProperty("fetched_at",fetched);
                root.add("windows",new Gson().toJsonTree(windows));
                Files.createDirectories(cache.toAbsolutePath().getParent());
                Path pending=Files.createTempFile(cache.toAbsolutePath().getParent(),"siliconflow-metadata-",".tmp");
                try {Files.writeString(pending,root.toString(),StandardCharsets.UTF_8);
                    Files.move(pending,cache,StandardCopyOption.REPLACE_EXISTING);}
                finally {Files.deleteIfExists(pending);}
            }
        } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
          catch(Exception ignored) { /* Keep the last valid public metadata on failure. */ }
    }
    static Map<String,Long> parse(String html) {
        var result=new LinkedHashMap<String,Long>();
        var flight=new StringBuilder();
        String marker="self.__next_f.push(";
        int cursor=0;
        while((cursor=html.indexOf(marker,cursor))>=0) {
            int start=cursor+marker.length(),end=start,depth=0;
            boolean quoted=false,escaped=false,found=false;
            for(;end<html.length();end++) {
                char c=html.charAt(end);
                if(quoted) {
                    if(escaped) escaped=false;
                    else if(c=='\\') escaped=true;
                    else if(c=='\"') quoted=false;
                } else if(c=='\"') quoted=true;
                else if(c=='[') depth++;
                else if(c==']' && --depth==0) {found=true;break;}
            }
            cursor=found ? end+1 : start;
            if(!found) continue;
            try {
                var chunk=JsonParser.parseString(html.substring(start,end+1)).getAsJsonArray();
                if(chunk.size()>1 && chunk.get(0).getAsInt()==1 && chunk.get(1).isJsonPrimitive())
                    flight.append(chunk.get(1).getAsString());
            }catch(RuntimeException ignored) {}
        }
        for(String line:flight.toString().split("\n")) {
            int colon=line.indexOf(':'); if(colon<0) continue;
            String value=line.substring(colon+1).stripLeading();
            if(!value.startsWith("[") && !value.startsWith("{")) continue;
            try {collect(JsonParser.parseString(value),result,0);} catch(RuntimeException ignored) {}
        }
        return result;
    }
    private static void collect(JsonElement value,Map<String,Long> result,int depth) {
        if(depth>96 || value==null || value.isJsonNull()) return;
        if(value.isJsonArray()) {for(var item:value.getAsJsonArray()) collect(item,result,depth+1);}
        else if(value.isJsonObject()) {
            var object=value.getAsJsonObject();
            if(object.has("modelName") && object.has("contextLen")) {
                try {
                    String id=object.get("modelName").getAsString();long size=object.get("contextLen").getAsLong();
                    if(!id.isBlank() && size>0 && size<=Integer.MAX_VALUE/2) result.put(id,size);
                } catch(RuntimeException ignored) {}
            }
            for(var child:object.entrySet()) collect(child.getValue(),result,depth+1);
        }
    }
}
