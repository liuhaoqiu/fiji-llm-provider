/*
 * SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
 * Copyright (c) 2026 Haoqiu Liu.
 * See LICENSE.txt and NOTICE.txt.
 */
package sc.fiji.llm.deepseek;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

/** Offline transport, streaming, tool-call and error-path verification. */
public final class TransportVerificationMain {

	private enum Mode {
		TEXT, STREAM, TOOL, UNAUTHORIZED
	}

	private static final AtomicReference<Mode> MODE = new AtomicReference<>(
		Mode.TEXT);
	private static final AtomicReference<String> LAST_BODY = new AtomicReference<>(
		"");
	private static final AtomicReference<String> SERVER_FAILURE =
		new AtomicReference<>();
	private static final AtomicBoolean SAW_TOOL_OUTPUT = new AtomicBoolean(false);

	private TransportVerificationMain() {}

	public static void main(final String[] args) throws Exception {
		final HttpServer server = HttpServer.create(new InetSocketAddress(
			"127.0.0.1", 0), 0);
		server.createContext("/responses", TransportVerificationMain::handle);
		server.createContext("/models", exchange -> sendJson(exchange, 200,
			"{\"data\":[{\"id\":\"deepseek-flash\",\"input_modalities\":[\"text\",\"image\"]," +
			"\"context_window\":1048576,\"max_output_tokens\":393216," +
			"\"effort\":{\"supported_levels\":[\"high\"],\"default_level\":\"high\"}}]}"));
		server.start();

		try {
			final String baseUrl = "http://127.0.0.1:" + server.getAddress()
				.getPort();
			final TestProvider provider = new TestProvider(baseUrl);
			verifyText(provider);
			verifyStreaming(provider);
			verifyToolRoundTrip(provider);
			verifyUnauthorized(provider);
			check(SERVER_FAILURE.get() == null, "mock server assertions: " +
				SERVER_FAILURE.get());
			System.out.println(
				"PASS: /responses transport, SSE, tool round-trip and 401 path");
		}
		finally {
			server.stop(0);
		}
	}

	private static void verifyText(final TestProvider provider) {
		MODE.set(Mode.TEXT);
		final ChatModel model = provider.createChatModel("deepseek-flash");
		check("pong".equals(model.chat("ping")), "synchronous response text");
		final String body = LAST_BODY.get();
		final String compactBody = body.replaceAll("\\s+", "");
		check(compactBody.contains("\"model\":\"deepseek-flash\""),
			"synchronous model id");
		check(compactBody.contains("\"effort\":\"high\""),
			"reasoning effort serialization");
	}

	private static void verifyStreaming(final TestProvider provider)
		throws InterruptedException
	{
		MODE.set(Mode.STREAM);
		final StreamingChatModel model = provider.createStreamingChatModel(
			"deepseek-flash");
		final StringBuilder partial = new StringBuilder();
		final AtomicReference<ChatResponse> complete = new AtomicReference<>();
		final AtomicReference<Throwable> error = new AtomicReference<>();
		final CountDownLatch done = new CountDownLatch(1);

		model.chat("stream", new StreamingChatResponseHandler() {

			@Override
			public void onPartialResponse(final String text) {
				partial.append(text);
			}

			@Override
			public void onCompleteResponse(final ChatResponse response) {
				complete.set(response);
				done.countDown();
			}

			@Override
			public void onError(final Throwable throwable) {
				error.set(throwable);
				done.countDown();
			}
		});

		check(done.await(10, TimeUnit.SECONDS), "stream completion timeout");
		check(error.get() == null, "stream error: " + error.get());
		check("stream-ok".contentEquals(partial), "stream text deltas");
		check(complete.get() != null, "stream complete response");
		check("stream-ok".equals(complete.get().aiMessage().text()),
			"stream final response");
	}

	private static void verifyToolRoundTrip(final TestProvider provider) {
		MODE.set(Mode.TOOL);
		SAW_TOOL_OUTPUT.set(false);
		final ChatModel model = provider.createChatModel("deepseek-flash");
		final ToolSpecification tool = ToolSpecification.builder().name(
			"fiji_test_tool").description("Offline Fiji tool transport test")
			.parameters(JsonObjectSchema.builder().addIntegerProperty("value")
				.required("value").additionalProperties(false).build()).build();
		final UserMessage user = UserMessage.from("Use the test tool");
		final ChatResponse first = model.chat(ChatRequest.builder().messages(user)
			.toolSpecifications(tool).build());
		final AiMessage aiMessage = first.aiMessage();
		check(aiMessage.hasToolExecutionRequests(), "parsed function call");
		check(aiMessage.toolExecutionRequests().size() == 1,
			"one function call");
		final ToolExecutionRequest request = aiMessage.toolExecutionRequests().get(0);
		check("call_1".equals(request.id()), "function call id");
		check("fiji_test_tool".equals(request.name()), "function call name");
		check("{\"value\":1}".equals(request.arguments()),
			"function call arguments");

		final ToolExecutionResultMessage result = ToolExecutionResultMessage.from(
			request, "tool-ok");
		final ChatResponse second = model.chat(ChatRequest.builder().messages(user,
			aiMessage, result).toolSpecifications(tool).build());
		check(SAW_TOOL_OUTPUT.get(), "function_call_output serialization");
		check("tool-roundtrip-ok".equals(second.aiMessage().text()),
			"tool round-trip response");

		final String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5xkAAAAASUVORK5CYII=";
		final var imageResult = ToolExecutionResultMessage.builder().id(request.id())
			.toolName(request.name()).contents(List.of(
				dev.langchain4j.data.message.TextContent.from("tool-ok"),
				dev.langchain4j.data.message.ImageContent.from(png, "image/png"))).build();
		final var memory = provider.createTokenChatMemory("deepseek-flash");
		memory.add(user);
		memory.add(aiMessage);
		memory.add(imageResult);
		final var imageResponse = model.chat(ChatRequest.builder().messages(memory.messages())
			.toolSpecifications(tool).build());
		check("tool-roundtrip-ok".equals(imageResponse.aiMessage().text()), "image tool round-trip");
		final var json = com.google.gson.JsonParser.parseString(LAST_BODY.get()).getAsJsonObject();
		boolean foundImage = false;
		for (var item : json.getAsJsonArray("input")) {
			var entry = item.getAsJsonObject();
			if (!"function_call_output".equals(entry.get("type").getAsString())) continue;
			var output = entry.getAsJsonArray("output");
			check(output.size() == 2, "image and text content preserved");
			check("input_image".equals(output.get(1).getAsJsonObject().get("type").getAsString()), "image type");
			check(("data:image/png;base64," + png).equals(output.get(1).getAsJsonObject()
				.get("image_url").getAsString()), "image bytes preserved on wire");
			foundImage = true;
		}
		check(foundImage, "image tool output found");
	}

	private static void verifyUnauthorized(final TestProvider provider) {
		MODE.set(Mode.UNAUTHORIZED);
		try {
			provider.createChatModel("deepseek-flash").chat("unauthorized");
			throw new AssertionError("FAILED: 401 response did not fail");
		}
		catch (final RuntimeException expected) {
			final String message = String.valueOf(expected.getMessage());
			check(message.contains("401") || message.contains("invalid_api_key") ||
				message.contains("invalid test key"), "401 error visibility: " +
				message);
		}
	}

	private static void handle(final HttpExchange exchange) throws IOException {
		try {
			checkServer("POST".equals(exchange.getRequestMethod()),
				"request method");
			checkServer("/responses".equals(exchange.getRequestURI().getPath()),
				"request path");
			checkServer("Bearer test-key".equals(exchange.getRequestHeaders()
				.getFirst("Authorization")), "authorization header");
			final String body = new String(exchange.getRequestBody().readAllBytes(),
				StandardCharsets.UTF_8);
			LAST_BODY.set(body);

			switch (MODE.get()) {
				case TEXT -> sendJson(exchange, 200, textResponse("resp_text", "pong"));
				case STREAM -> sendStream(exchange);
				case TOOL -> {
					checkServer(body.contains("\"tools\""),
						"tool specification serialization");
					if (body.contains("function_call_output")) {
						checkServer(body.contains("call_1"), "tool call id round-trip");
						checkServer(body.contains("tool-ok"), "tool result round-trip");
						SAW_TOOL_OUTPUT.set(true);
						sendJson(exchange, 200, textResponse("resp_tool_done",
							"tool-roundtrip-ok"));
					}
					else sendJson(exchange, 200, toolResponse());
				}
				case UNAUTHORIZED -> sendJson(exchange, 401,
					"{\"error\":{\"message\":\"invalid test key\",\"type\":" +
						"\"authentication_error\",\"code\":\"invalid_api_key\"}}");
			}
		}
		catch (final Throwable failure) {
			SERVER_FAILURE.compareAndSet(null, failure.toString());
			if (exchange.getResponseCode() == -1) sendJson(exchange, 500,
				"{\"error\":{\"message\":\"mock failure\"}}");
		}
	}

	private static void sendStream(final HttpExchange exchange)
		throws IOException
	{
		exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream output = exchange.getResponseBody()) {
			writeEvent(output,
				"{\"type\":\"response.output_text.delta\",\"sequence_number\":0," +
					"\"item_id\":\"msg_stream\",\"output_index\":0," +
					"\"content_index\":0,\"delta\":\"stream-\"}");
			writeEvent(output,
				"{\"type\":\"response.output_text.delta\",\"sequence_number\":1," +
					"\"item_id\":\"msg_stream\",\"output_index\":0," +
					"\"content_index\":0,\"delta\":\"ok\"}");
			writeEvent(output,
				"{\"type\":\"response.completed\",\"sequence_number\":2," +
					"\"response\":" + textResponse("resp_stream", "stream-ok") +
					"}");
		}
	}

	private static void writeEvent(final OutputStream output, final String json)
		throws IOException
	{
		output.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
		output.flush();
	}

	private static String textResponse(final String id, final String text) {
		return "{\"id\":\"" + id + "\",\"object\":\"response\"," +
			"\"model\":\"deepseek-flash\",\"status\":\"completed\"," +
			"\"output\":[{\"id\":\"msg_1\",\"type\":\"message\"," +
			"\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{" +
			"\"type\":\"output_text\",\"text\":\"" + text +
			"\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":1," +
			"\"output_tokens\":1,\"total_tokens\":2}}";
	}

	private static String toolResponse() {
		return "{\"id\":\"resp_tool\",\"object\":\"response\"," +
			"\"model\":\"deepseek-flash\",\"status\":\"completed\"," +
			"\"output\":[{\"id\":\"fc_1\",\"type\":\"function_call\"," +
			"\"call_id\":\"call_1\",\"name\":\"fiji_test_tool\"," +
			"\"arguments\":\"{\\\"value\\\":1}\",\"status\":\"completed\"}]," +
			"\"usage\":{\"input_tokens\":1,\"output_tokens\":1," +
			"\"total_tokens\":2}}";
	}

	private static void sendJson(final HttpExchange exchange, final int status,
		final String json) throws IOException
	{
		final byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static void checkServer(final boolean condition, final String label) {
		if (!condition) throw new AssertionError("mock server: " + label);
	}

	private static void check(final boolean condition, final String label) {
		if (!condition) throw new AssertionError("FAILED: " + label);
	}

	private static final class TestProvider extends DeepSeekProvider {

		private final String testBaseUrl;

		private TestProvider(final String testBaseUrl) {
			this.testBaseUrl = testBaseUrl;
		}

		@Override
		protected String baseUrl() {
			return testBaseUrl;
		}

		@Override
		protected java.nio.file.Path modelCacheFile() { return null; }

		@Override
		protected String apiKey() {
			return "test-key";
		}

		@Override
		protected List<ChatModelListener> listeners() {
			return List.of();
		}
	}
}
