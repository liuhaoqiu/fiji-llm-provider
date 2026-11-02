/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.util.List;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;

/** Local memory estimate only; leaves the original multimodal payload untouched. */
final class MultimodalTokenCountEstimator extends OpenAiTokenCountEstimator {
    // Budget allowance, not an exact DeepSeek image-token count. No image fetches
    // or base64 tokenization are performed while managing conversation memory.
    private static final int IMAGE_TOKEN_ALLOWANCE = 16_384;

    MultimodalTokenCountEstimator() {
        super("gpt-4o");
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
        if (message instanceof ToolExecutionResultMessage tool) {
            return 4 + countContents(tool.contents());
        }
        if (message instanceof UserMessage user) {
            return 4 + countContents(user.contents()) + (user.name() == null ? 0 :
                1 + estimateTokenCountInText(user.name()));
        }
        return super.estimateTokenCountInMessage(message);
    }

    private int countContents(List<Content> contents) {
        int count = 0;
        for (Content content : contents) {
            if (content instanceof TextContent text) {
                count += estimateTokenCountInText(text.text());
            } else if (content instanceof ImageContent) {
                count += IMAGE_TOKEN_ALLOWANCE;
            } else {
                throw new IllegalArgumentException("Unsupported memory content type: " +
                    content.getClass().getSimpleName());
            }
        }
        return count;
    }
}
