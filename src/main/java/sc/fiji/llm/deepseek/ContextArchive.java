/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;

/** Full-fidelity local record, independent of the model's sliding memory. */
final class ContextArchive {
    final Path directory;
    private final Map<ChatMessage, String> entries = new IdentityHashMap<>();
    private int sequence;
    private int checkpoints;
    private UserMessage task;
    private UserMessage initialTask;
    private final List<UserMessage> userInstructions = new ArrayList<>();

    ContextArchive() {
        String override = System.getProperty("fiji.llm.contextArchiveRoot");
        String local = System.getenv("LOCALAPPDATA");
        Path root = override != null ? Path.of(override) :
            (local == null ? Path.of(System.getProperty("user.home"), ".cache") : Path.of(local))
                .resolve("FijiChat").resolve("context-archives");
        directory = root.resolve(UUID.randomUUID().toString());
    }

    synchronized String store(ChatMessage message) {
        String existing = entries.get(message);
        if (existing != null) return existing;
        String name = String.format(java.util.Locale.ROOT, "message-%08d.json", ++sequence);
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(name), ChatMessageSerializer.messageToJson(message),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            // The index records additions, including repeated equal messages.
            Files.writeString(directory.resolve("index.txt"), name + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            entries.put(message, name);
            return name;
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to preserve the full context archive: " + directory, failure);
        }
    }

    synchronized void snapshot(List<ChatMessage> messages) {
        for (var message : messages) store(message);
    }

    synchronized void task(UserMessage message) {
        if (message == null) { task = initialTask = null; userInstructions.clear(); return; }
        if (message.hasSingleText() && message.singleText().startsWith("Original task excerpt:") &&
            message.singleText().contains("EXTRACTIVE HISTORY")) return;
        if (initialTask == null) initialTask = message;
        if (!message.equals(initialTask) && !userInstructions.contains(message)) {
            userInstructions.add(message);
            while (userInstructions.size() > 3) userInstructions.remove(0);
        }
        if (message != null && task != null && message.hasSingleText() &&
            message.singleText().length() < 100 &&
            message.singleText().matches("(?is).*?(continue|resume|继续).*")) return;
        task = message;
    }
    synchronized UserMessage task() { return task; }
    synchronized UserMessage initialTask() { return initialTask; }
    synchronized List<UserMessage> pinnedInstructions() {
        var result = new ArrayList<UserMessage>();
        if (initialTask != null) result.add(initialTask);
        result.addAll(userInstructions);
        return result;
    }

    synchronized void delta(String kind, String text) {
        try {
            Files.createDirectories(directory);
            String event = new com.google.gson.Gson().toJson(Map.of("kind", kind, "text", text,
                "timestamp", java.time.Instant.now().toString()));
            Files.writeString(directory.resolve("stream-events.jsonl"), event + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to preserve streamed progress: " + directory, failure);
        }
    }

    synchronized void checkpoint(String text) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(String.format(java.util.Locale.ROOT,
                "checkpoint-%08d.md", ++checkpoints)), text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(directory.resolve("checkpoint.md"), text, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to save task checkpoint: " + directory, failure);
        }
    }

    ChatMemory wrap(ChatMemory memory) {
        snapshot(memory.messages());
        UserMessage.findLast(memory.messages()).ifPresent(this::task);
        return new ChatMemory() {
            public Object id() { return memory.id(); }
            public void add(ChatMessage message) {
                store(message);
                if (message instanceof UserMessage user) task(user);
                memory.add(message);
            }
            public List<ChatMessage> messages() { return memory.messages(); }
            @Override public void set(Iterable<ChatMessage> messages) {
                var values = new ArrayList<ChatMessage>();
                for (var message : messages) { store(message); values.add(message); }
                memory.set(values);
            }
            public void clear() { memory.clear(); task(null); }
        };
    }
}
