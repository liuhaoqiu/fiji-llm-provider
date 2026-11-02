/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.util.List;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

public final class MultimodalVerificationMain {
    public static void main(String[] args) {
        ImageContent image = ImageContent.from(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5xkAAAAASUVORK5CYII=", "image/png");
        var request = ToolExecutionRequest.builder().id("image_1")
            .name("inspect_image").arguments("{}").build();
        var result = ToolExecutionResultMessage.builder().id(request.id())
            .toolName(request.name()).contents(List.of(TextContent.from("Image preview"), image)).build();
        try {
            new OpenAiTokenCountEstimator("gpt-4o").estimateTokenCountInMessage(result);
            throw new AssertionError("Old estimator should reproduce the failure");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("text() cannot be called")) throw expected;
            System.out.println("REPRODUCED: " + expected.getMessage());
        }
        var provider = new DeepSeekProvider() {
            @Override protected java.nio.file.Path modelCacheFile() { return null; }
        };
        var memory = provider.createTokenChatMemory("deepseek-flash");
        var user = UserMessage.from(TextContent.from("Adjust image contrast"), image);
        var imageMemory = provider.createTokenChatMemory("deepseek-flash");
        imageMemory.add(user);
        if (!imageMemory.messages().contains(user)) throw new AssertionError("User image lost");
        memory.add(UserMessage.from("Adjust image contrast"));
        memory.add(AiMessage.from(request));
        memory.add(result);
        if (memory.messages().size() != 3 || !memory.messages().contains(result)
            || !result.contents().get(1).equals(image)) throw new AssertionError("Image payload lost");
        var estimator = new MultimodalTokenCountEstimator();
        var text = ToolExecutionResultMessage.from("text", "tool", "plain result");
        if (estimator.estimateTokenCountInMessage(text) !=
            new OpenAiTokenCountEstimator("gpt-4o").estimateTokenCountInMessage(text))
            throw new AssertionError("Text regression");
        var multipleText = ToolExecutionResultMessage.builder().id("t2").toolName("tool")
            .contents(List.of(TextContent.from("first"), TextContent.from("second"))).build();
        if (estimator.estimateTokenCountInMessage(multipleText) <= 4)
            throw new AssertionError("Multiple text blocks not counted");
        if (estimator.estimateTokenCountInMessage(result) <= 16_384)
            throw new AssertionError("Image budget missing");
        var small = TokenWindowChatMemory.withMaxTokens(100, estimator);
        small.add(user);
        small.add(UserMessage.from("next turn"));
        if (small.messages().contains(user)) throw new AssertionError("Eviction broken");
        System.out.println("PASS: multimodal memory, payload preservation, text compatibility and eviction");
    }
}
