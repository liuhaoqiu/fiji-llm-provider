/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;

public final class TaskRetentionVerificationMain {
    public static void main(String[] args) throws Exception {
        eviction();
        if(args.length>0 && args[0].equals("eviction-only")) return;
        compaction(); metadata();
        System.out.println("PASS: task restoration after real image eviction, follow-up constraints, separate chats, image-result continuation, fresh-chat byte headroom, dynamic official metadata and cached-failure recovery");
    }
    private static void eviction() {
        var context=new BoundedContext(24576,16L*1048576);
        var memory=context.archive.wrap(TokenWindowChatMemory.withMaxTokens(24576,new MultimodalTokenCountEstimator(16384)));
        context.workingMemory=memory;context.imageTokenAllowance=16384;
        memory.add(SystemMessage.from("Use Fiji tools to perform the user's task."));
        var goal=UserMessage.from("Segment red nuclei using a macro, count them, and compare with Cellpose. Preserve the original pixels.");
        memory.add(goal);
        context.prepare(ChatRequest.builder().messages(memory.messages()).build());
        for(int i=0;i<2;i++) {
            var call=call("view"+i);
            memory.add(AiMessage.from(call));memory.add(image(call,"AA=="));
        }
        check(!memory.messages().contains(goal),"fixture must actually evict the task in TokenWindowChatMemory");
        var outgoing=context.prepare(ChatRequest.builder().messages(memory.messages()).build());
        check(outgoing.messages().contains(goal),"outgoing request must restore task after real image eviction, including under-budget requests");
        check(memory.messages().contains(goal),"restored task persists in working memory");
        var followup=UserMessage.from("Have you counted them and compared the Cellpose result yet?");memory.add(followup);
        var next=context.prepare(ChatRequest.builder().messages(memory.messages()).build());
        check(next.messages().contains(goal)&&next.messages().contains(followup),"follow-up does not erase original constraints");
        var other=new BoundedContext(24576,16L*1048576);
        var fresh=other.prepare(ChatRequest.builder().messages(List.of(UserMessage.from("New independent task"))).build());
        check(!fresh.messages().contains(goal)&&!other.archive.directory.equals(context.archive.directory),"new chats cannot inherit task pins or archive IDs");
    }
    private static void compaction() {
        var context=new BoundedContext(983040,16L*1048576);
        var goal=UserMessage.from("Count red nuclei with a macro, then compare with Cellpose.");
        var first=call("first");var originalImage=image(first,"A".repeat(13_800_000));
        var firstRequest=ChatRequest.builder().messages(List.of(goal,AiMessage.from(first),originalImage)).build();
        check(context.prepare(firstRequest).messages().contains(originalImage)&&context.latestCheckpoint.isEmpty(),
            "one 13.8MB image in a 1M fresh chat must not prematurely compact under the 16MiB body policy");
        var next=call("second");var nextImage=image(next,"B".repeat(13_800_000));
        var compact=context.prepare(ChatRequest.builder().messages(List.of(goal,AiMessage.from(first),originalImage,
            AiMessage.from(next),nextImage)).build());
        check(compact.messages().contains(goal)&&compact.messages().contains(nextImage)&&!compact.messages().contains(originalImage),
            "body compaction keeps the task and latest complete tool group while archiving the older image");
        check(context.latestCheckpoint.contains("request-body byte budget"),"compaction discloses byte pressure separately from token window");
        var normalized=context.normalizeImageResults(compact);
        check(normalized.messages().stream().filter(m->m instanceof UserMessage).map(m->(UserMessage)m)
            .anyMatch(u->u.contents().stream().anyMatch(c->c instanceof TextContent t&&t.text().contains("NOT a new or empty user request"))),
            "tool images are labeled as continuation evidence, not a new empty user message");
        context.archive.task(UserMessage.from("Original task excerpt: synthetic summary\nEXTRACTIVE HISTORY"));
        check(context.archive.task().equals(goal),"synthetic summaries cannot overwrite the human task");
        var checkpoint=AiMessage.from("Task paused — not completed. Count/comparison still pending.");
        check(sc.fiji.llm.chat.ChatMessageConverter.fromSerialized(sc.fiji.llm.chat.ChatMessageConverter.toSerialized(checkpoint)).equals(checkpoint),
            "paused replies survive the actual Fiji history serialization path");
    }
    private static void metadata() throws Exception {
        var a=new JsonObject();a.addProperty("modelName","fixture-v1");a.addProperty("contextLen",1048576);
        var b=new JsonObject();b.addProperty("modelName","fixture-future-v9");b.addProperty("contextLen",262144);
        var data=new JsonArray();data.add(a);data.add(b);var root=new JsonObject();root.add("data",data);
        root.addProperty("longServerRenderedText","escaped official-page content \"quote\" \\ slash ".repeat(5000));
        String flight="0:"+root+"\n";int cut=flight.length()/2;
        var chunks=new ArrayList<String>();
        for(String part:List.of(flight.substring(0,cut),flight.substring(cut))) {
            var chunk=new JsonArray();chunk.add(1);chunk.add(part);chunks.add("<script>self.__next_f.push("+chunk+")</script>");
        }
        String html=String.join("",chunks);
        var parsed=SiliconFlowModelMetadata.parse(html);
        check(parsed.get("fixture-v1")==1048576&&parsed.get("fixture-future-v9")==262144,"parse future model IDs across escaped Next.js chunks without a hardcoded catalog");
        check(SiliconFlowModelMetadata.parse("<html>format changed</html>").isEmpty(),"unknown public-page format is not invented metadata");
        var body=new AtomicReference<>(html);var error=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/models",e->{
            if(e.getRequestHeaders().getFirst("Authorization")!=null)error.set("credential leaked to public metadata endpoint");
            byte[] bytes=body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);
            try(var out=e.getResponseBody()){out.write(bytes);}finally{e.close();}
        });server.start();
        try {
            Path cache=Path.of(System.getProperty("fiji.llm.contextArchiveRoot")).resolve("metadata-fixture.json");
            URI url=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/models");
            var source=new SiliconFlowModelMetadata(cache,url,Duration.ofSeconds(2));
            check(source.window("fixture-v1")==1048576,"fetch official metadata separately from API model IDs");
            body.set("<html>unavailable</html>");source.refresh(true);
            check(source.window("fixture-v1")==1048576,"failed refresh retains last valid metadata");
            var reopened=new SiliconFlowModelMetadata(cache,url,Duration.ofMillis(100));
            check(reopened.window("fixture-future-v9")==262144,"public metadata cache survives provider reconstruction");
            check(error.get()==null,"metadata request must not use API credentials");
        }finally{server.stop(0);}
    }
    private static ToolExecutionRequest call(String id){return ToolExecutionRequest.builder().id(id).name("fiji_image_view").arguments("{}").build();}
    private static ToolExecutionResultMessage image(ToolExecutionRequest call,String data){return ToolExecutionResultMessage.builder().id(call.id()).toolName(call.name())
        .contents(List.of(TextContent.from("Synthetic image result"),ImageContent.from(data,"image/png"))).build();}
    private static void check(boolean condition,String diagnostic){if(!condition)throw new AssertionError(diagnostic);}
}
