/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.time.Duration;
import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.*;

/** Measures the fully serialized JSON, including images and reasoning metadata. */
final class BudgetHttpClientBuilder implements HttpClientBuilder {
    private final HttpClientBuilder delegate;
    private final long maxBytes;

    BudgetHttpClientBuilder(HttpClientBuilder delegate, long maxBytes) {
        this.delegate = delegate;
        this.maxBytes = maxBytes;
    }
    @Override public Duration connectTimeout() { return delegate.connectTimeout(); }
    @Override public Duration readTimeout() { return delegate.readTimeout(); }
    @Override public HttpClientBuilder connectTimeout(Duration value) {
        delegate.connectTimeout(value); return this;
    }
    @Override public HttpClientBuilder readTimeout(Duration value) {
        delegate.readTimeout(value); return this;
    }
    @Override public HttpClient build() {
        var client = delegate.build();
        return new HttpClient() {
            @Override public SuccessfulHttpResponse execute(HttpRequest request) {
                RequestBudget.checkBytes(request.body(), maxBytes);
                return client.execute(request);
            }
            @Override public void execute(HttpRequest request, ServerSentEventParser parser,
                ServerSentEventListener listener) {
                RequestBudget.checkBytes(request.body(), maxBytes);
                var guard = new IdleSseGuard(parser, listener, delegate.readTimeout());
                client.execute(request, guard, guard.listener);
            }
        };
    }
}
