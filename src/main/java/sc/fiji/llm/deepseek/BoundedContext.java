/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;

/** Extractive compaction: retains the task and complete recent tool-call groups.
 * It does not claim that a tool ran successfully or that an excerpt is complete.
 * Full messages, including images and reasoning attributes, live in the archive.
 */
final class BoundedContext {
    final ContextArchive archive = new ContextArchive();
    final long inputLimit, byteLimit;
    volatile Consumer<String> progress = text -> {};
    volatile Consumer<String> stageOutput = text -> {};
    volatile String latestCheckpoint = "";
    volatile boolean cancelled;
    volatile Runnable requestStarting = () -> {};
    String label = "LLM task";
    int imageTokenAllowance = 16384;
    dev.langchain4j.memory.ChatMemory workingMemory;
    private double tokenPressure = 1.0, bytePressure = 1.0;
    private ChatRequest lastRequest;
    private int checkpoints;
    private String compactionReason = "";

    BoundedContext(long inputLimit, long byteLimit) {
        this.inputLimit = inputLimit;
        this.byteLimit = byteLimit;
    }

    ChatRequest prepare(ChatRequest request) {
        if (cancelled) throw new java.util.concurrent.CancellationException("Stopped by user");
        requestStarting.run();
        for (var message : request.messages()) if (message instanceof UserMessage user) archive.task(user);
        var beforeRestore = request;
        request = restoreInstructions(request);
        var source = request.messages();
        lastRequest = request;
        archive.snapshot(source);
        ContextMonitor.preflight(label, RequestBudget.estimatedTokens(request, imageTokenAllowance), inputLimit, byteLimit, archive.directory.toString());
        var task = archive.task();
        if (!over(request)) {
            if (request != beforeRestore && workingMemory != null) workingMemory.set(source);
            return request;
        }
        compactionReason = RequestBudget.estimatedTokens(request, imageTokenAllowance) > inputLimit * 0.8 * tokenPressure
            ? "estimated-token budget" : "request-body byte budget (not the model token window)";
        // Avoid splitting an assistant tool-call batch from any of its results.
        var starts = new ArrayList<Integer>();
        for (int i = 0; i < source.size(); i++)
            if (source.get(i) instanceof AiMessage) starts.add(i);
        // A new user turn after a final assistant answer is also a safe boundary.
        int taskIndex = source.lastIndexOf(task);
        if (taskIndex > 0 && (starts.isEmpty() || taskIndex > starts.get(starts.size() - 1))) starts.add(taskIndex);
        ChatRequest result = request;
        for (int keep = Math.min(6, starts.size()); keep >= 1; keep--) {
            int cut = starts.get(starts.size() - keep);
            var retained = new ArrayList<ChatMessage>();
            for (var message : source) if (message instanceof SystemMessage) retained.add(message);
            String summary = summary(source.subList(0, cut), task);
            retained.add(UserMessage.from(summary));
            for (var instruction : archive.pinnedInstructions())
                if (!source.subList(cut, source.size()).contains(instruction)) retained.add(instruction);
            retained.addAll(source.subList(cut, source.size()));
            result = request.toBuilder().messages(retained).build();
            if (!over(result)) {
                publish(summary, result);
                if (workingMemory != null) workingMemory.set(result.messages());
                return result;
            }
        }
        // Soft thresholds initiate organization, not a smaller advertised model
        // window. A current batch that still fits the hard limit may proceed.
        if (!exceedsHard(result) || !exceedsHard(request)) {
            ChatRequest usable = !exceedsHard(result) ? result : request;
            publish(summary(source, task), usable);
            if (workingMemory != null) workingMemory.set(usable.messages());
            return usable;
        }
        // A single large latest result can be excerpted, but its call ID and
        // error marker must survive. Recent images and opaque reasoning stay intact.
        var excerpted = new ArrayList<ChatMessage>();
        for (var message : result.messages()) {
            if (message instanceof ToolExecutionResultMessage tool && tool.hasSingleText()
                && tool.text().length() > 8192) {
                excerpted.add(tool.toBuilder().contents(List.of(TextContent.from(
                    "[EXCERPT ONLY; full result: " + archive.directory.resolve(archive.store(tool)) + "]\n" +
                    excerpt(tool.text(), 8192)))).build());
            } else excerpted.add(message);
        }
        result = result.toBuilder().messages(excerpted).build();
        if (over(result)) {
            latestCheckpoint = summary(source, task) + "\n\nPaused: the current task/recent tool batch " +
                "still exceeds the configured context budget. No new model request was sent.";
            archive.checkpoint(latestCheckpoint);
            throw new IllegalArgumentException("Current task or recent tool batch cannot fit safely. " +
                "Progress checkpoint: " + archive.directory.resolve("checkpoint.md"));
        }
        publish(summary(source, task), result);
        if (workingMemory != null) workingMemory.set(result.messages());
        return result;
    }

    private ChatRequest restoreInstructions(ChatRequest request) {
        var messages = new ArrayList<>(request.messages());
        var pinned = archive.pinnedInstructions();
        for (int i = 0; i < pinned.size(); i++) {
            var instruction = pinned.get(i);
            if (messages.contains(instruction)) continue;
            int position = -1;
            for (int j = i + 1; j < pinned.size(); j++) {
                int next = messages.indexOf(pinned.get(j));
                if (next >= 0) { position = next; break; }
            }
            if (position < 0) {
                position = 0;
                while (position < messages.size() && (messages.get(position) instanceof SystemMessage ||
                    pinned.contains(messages.get(position)))) position++;
            }
            messages.add(position, instruction);
        }
        return messages.equals(request.messages()) ? request : request.toBuilder().messages(messages).build();
    }

    ChatRequest normalizeImageResults(ChatRequest request) {
        var normalized = sc.fiji.llm.chat.ImageToolResultNormalizer.normalize(request);
        if (normalized == request) return request;
        var messages = new ArrayList<ChatMessage>();
        for (var message : normalized.messages()) {
            if (message instanceof UserMessage user && user.contents().stream().anyMatch(c -> c instanceof ImageContent) &&
                user.contents().stream().anyMatch(c -> c instanceof TextContent t && t.text().equals("Image content returned by tool."))) {
                var contents = new ArrayList<Content>();
                contents.add(TextContent.from("Image content returned by a tool for the existing task; this is NOT a new or empty user request. " +
                    "Continue the latest user instruction and the earlier task constraints from the conversation. " +
                    "Use the image as tool evidence; do not restart the conversation or ask for an already explicit goal."));
                for (var content : user.contents()) if (!(content instanceof TextContent t && t.text().equals("Image content returned by tool."))) contents.add(content);
                messages.add(user.toBuilder().contents(contents).build());
            } else messages.add(message);
        }
        return normalized.toBuilder().messages(messages).build();
    }

    private boolean over(ChatRequest request) {
        long messageBytes = ChatMessageSerializer.messagesToJson(request.messages())
            .getBytes(StandardCharsets.UTF_8).length;
        // Leave room for protocol encoding, schemas and the next model/tool reply.
        return RequestBudget.estimatedTokens(request, imageTokenAllowance) > inputLimit * 0.8 * tokenPressure ||
            messageBytes > byteLimit * 0.9 * bytePressure;
    }

    private boolean exceedsHard(ChatRequest request) {
        return RequestBudget.estimatedTokens(request, imageTokenAllowance) > inputLimit * tokenPressure ||
            ChatMessageSerializer.messagesToJson(request.messages()).getBytes(StandardCharsets.UTF_8).length >
                byteLimit * 0.9 * bytePressure;
    }

    private void publish(String summary, ChatRequest retained) {
        latestCheckpoint = "## 阶段记录 / Progress checkpoint\n\n" + summary +
            "\n\n旧上下文已整理，继续执行 / Older context compacted; execution continues.\n" +
            "Trigger: " + compactionReason + ". Input budget: " + inputLimit + " tokens; body budget: " + byteLimit + " bytes.\n" +
            "Estimated retained input: " + RequestBudget.estimatedTokens(retained, imageTokenAllowance) + " tokens.\n";
        archive.checkpoint(latestCheckpoint);
        ContextMonitor.preflight(label, RequestBudget.estimatedTokens(retained, imageTokenAllowance), inputLimit, byteLimit, archive.directory.toString());
        // Avoid flooding Swing's HTML renderer while an already-long original
        // memory is projected into the smaller working context on each round.
        if (++checkpoints == 1 || checkpoints % 10 == 0)
            progress.accept("\n\n" + excerpt(latestCheckpoint, 2000) +
                "\nFull checkpoint: " + archive.directory.resolve("checkpoint.md") + "\n\n");
        if (cancelled) throw new java.util.concurrent.CancellationException("Stopped by user");
    }

    String interrupted(Throwable error) {
        // A gateway can impose a smaller cap than its published model limits.
        // The next user continuation must not simply resend the same payload.
        var source = lastRequest == null ? List.<ChatMessage>of() : lastRequest.messages();
        long previousBytes = ChatMessageSerializer.messagesToJson(source).getBytes(StandardCharsets.UTF_8).length;
        String diagnostic = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (diagnostic.contains("length limit exceeded") || diagnostic.contains("413") ||
            diagnostic.contains("request bytes") || diagnostic.contains("request entity too large")) {
            bytePressure = Math.max(0.001, Math.min(bytePressure * 0.5,
                previousBytes * 0.5 / (byteLimit * 0.9)));
        } else tokenPressure = Math.max(0.05, tokenPressure * 0.5);
        latestCheckpoint = "## 任务暂停 / Task paused — not completed\n\n" +
            "上下文或请求体超出可用空间；已保留阶段记录，可在本对话中输入“继续”。\n" +
            "Context/request-size limit reached. Progress was preserved; reply ‘continue’ in this conversation.\n\n" +
            summary(source, archive.task()) + "\n\nDiagnostic: " + error.getClass().getSimpleName() +
            ". The failed model request did not rerun any tools.\n" +
            "Progress checkpoint: " + archive.directory.resolve("checkpoint.md") + "\n";
        archive.checkpoint(latestCheckpoint);
        return latestCheckpoint;
    }

    String stageSummary() {
        if (lastRequest != null) {
            var messages = lastRequest.messages();
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof ToolExecutionResultMessage tool) {
                    String preview = "";
                    for (var content : tool.contents()) if (content instanceof TextContent text) {
                        preview = excerpt(text.text(), 120).replaceAll("\\s+", " "); break;
                    }
                    return "**阶段进度 / Progress**：上下文已整理（" + compactionReason + "），任务继续。最近返回的工具：`" +
                        tool.toolName() + "`。结果摘录（未独立验证）：" + preview;
                }
            }
        }
        return "**阶段进度 / Progress**：上下文已整理，任务继续；详细过程见运行日志。";
    }

    String transportInterrupted(Throwable error) {
        var source = lastRequest == null ? List.<ChatMessage>of() : lastRequest.messages();
        latestCheckpoint = "## 任务暂停 / Task paused — not completed\n\n" +
            "API 请求超时、中断，或模型服务未返回可用的最终回复，后续工作尚未完成。阶段记录已保存；可在本对话中输入“继续”。\n" +
            "The API request timed out, was interrupted, or the model service returned no usable final answer. Further work is not completed. " +
            "Progress was preserved; reply ‘continue’ in this conversation.\n\n" +
            summary(source, archive.task()) + "\n\nDiagnostic: " + error.getClass().getSimpleName() +
            ". No tools were automatically replayed. A request may have reached the provider.\n" +
            "Progress checkpoint: " + archive.directory.resolve("checkpoint.md") + "\n";
        archive.checkpoint(latestCheckpoint);
        return latestCheckpoint;
    }

    static boolean isTransportFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketException ||
                cause instanceof java.net.UnknownHostException || cause instanceof javax.net.ssl.SSLException ||
                cause instanceof java.io.EOFException || cause instanceof java.util.concurrent.TimeoutException) return true;
            String text = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (text.contains("request timed out") || text.contains("read timed out") ||
                text.contains("connection reset") || text.contains("unexpected end of stream") ||
                text.contains("sse stream idle timeout") || text.contains("request timeout") ||
                text.contains("model service timeout")) return true;
        }
        return false;
    }

    static boolean isContextFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String text = String.valueOf(cause.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (text.contains("length limit exceeded") || text.contains("context limit reached") ||
                text.contains("context_length_exceeded") || text.contains("maximum context length") ||
                text.contains("cannot fit safely") || text.contains("status code: 413") ||
                text.contains("status code 413") || text.contains("request entity too large")) return true;
        }
        return false;
    }

    String summary(List<ChatMessage> messages, UserMessage task) {
        var lines = new ArrayList<String>();
        for (var message : messages) {
            if (message instanceof SystemMessage || message == task) continue;
            if (message instanceof AiMessage ai) {
                if (ai.text() != null && !ai.text().isBlank())
                    lines.add("Assistant excerpt: " + excerpt(ai.text(), 400));
                if (ai.hasToolExecutionRequests()) for (var call : ai.toolExecutionRequests())
                    lines.add("Tool requested (not proof of success): " + call.name() + " " + excerpt(call.arguments(), 350));
            } else if (message instanceof ToolExecutionResultMessage tool) {
                String text = tool.contents().stream().filter(c -> c instanceof TextContent)
                    .map(c -> ((TextContent)c).text()).reduce("", (a,b) -> a + "\n" + b);
                lines.add("Tool result [" + tool.toolName() + ", error=" + tool.isError() + ", file=" +
                    archive.store(tool) + "]: " + excerpt(text, 650));
            } else if (message instanceof UserMessage user) {
                String text = user.contents().stream().filter(c -> c instanceof TextContent)
                    .map(c -> ((TextContent)c).text()).reduce("", (a,b) -> a + "\n" + b);
                lines.add("Earlier user instruction excerpt: " + excerpt(text, 500));
            }
        }
        int first = Math.max(0, lines.size() - 16);
        String taskText = task == null ? "" : task.contents().stream()
            .filter(c -> c instanceof TextContent).map(c -> ((TextContent)c).text())
            .reduce("", (a,b) -> a + "\n" + b);
        String initial = archive.initialTask() == null ? "" : archive.initialTask().contents().stream()
            .filter(c -> c instanceof TextContent).map(c -> ((TextContent)c).text()).reduce("", (a,b) -> a + "\n" + b);
        return "Original task excerpt: " + excerpt(initial.isBlank() ? taskText : initial, 1200) +
            "\n\nLatest substantive user instruction excerpt: " + excerpt(taskText, 1200) + "\n\n" +
            "EXTRACTIVE HISTORY — excerpts, not a verified completion report. " +
            "The latest user instruction takes precedence when the user changes the task. Restored snapshots/naming actions are historical, not fresh commands. " +
            "Read only the relevant archived message or script if needed, not the entire archive; do not repeat completed actions merely " +
            "because older messages are summarized. Full local record: " + archive.directory + "\n\n" +
            String.join("\n\n", lines.subList(first, lines.size())) +
            "\n\nNext: continue the original user task from the most recent tool results; " +
            "reopen the current script/image and verify any uncertain result before claiming success.";
    }

    static String excerpt(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        return text.substring(0, limit / 2) + "\n[... excerpt omitted; full text archived ...]\n" +
            text.substring(text.length() - limit / 2);
    }
}
