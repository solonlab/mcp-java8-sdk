/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol.server.legacy;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.var;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestTemplate;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

public class WebFluxSseIntegrationTests {

	private static final int PORT = 8182;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private DisposableServer httpServer;

	private WebFluxSseServerTransport mcpServerTransport;

	ConcurrentHashMap<String, McpClient.SyncSpec> clientBulders = new ConcurrentHashMap<>();

	@BeforeEach
	public void before() {

		this.mcpServerTransport = new WebFluxSseServerTransport(new ObjectMapper(), MESSAGE_ENDPOINT);

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(mcpServerTransport.getRouterFunction());
		ReactorHttpHandlerAdapter adapter = new ReactorHttpHandlerAdapter(httpHandler);
		this.httpServer = HttpServer.create().port(PORT).handle(adapter).bindNow();

		clientBulders.put("httpclient", McpClient.sync(new HttpClientSseClientTransport("http://localhost:" + PORT)));
		clientBulders.put("webflux",
				McpClient.sync(new WebFluxSseClientTransport(WebClient.builder().baseUrl("http://localhost:" + PORT))));

	}

	@AfterEach
	public void after() {
		if (httpServer != null) {
			httpServer.disposeNow();
		}
	}

	// ---------------------------------------
	// Sampling Tests
	// ---------------------------------------
	@Test
	void testCreateMessageWithoutInitialization() {
		var mcpAsyncServer = McpServer.async(mcpServerTransport).serverInfo("test-server", "1.0.0").build();

		var messages = Arrays.asList(new McpSchema.SamplingMessage(Role.USER, new McpSchema.TextContent("Test message")));
		var modelPrefs = new McpSchema.ModelPreferences(Collections.emptyList(), 1.0, 1.0, 1.0);

		var request = new CreateMessageRequest(messages, modelPrefs, null,
				CreateMessageRequest.ContextInclusionStrategy.NONE, null, 100, Collections.emptyList(),Collections.emptyMap());

		StepVerifier.create(mcpAsyncServer.createMessage(request)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Client must be initialized. Call the initialize method first!");
		});
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithoutSamplingCapabilities(String clientType) {

		var mcpAsyncServer = McpServer.async(mcpServerTransport).serverInfo("test-server", "1.0.0").build();

		var clientBuilder = clientBulders.get(clientType);

		var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0")).build();

		InitializeResult initResult = client.initialize();
		assertThat(initResult).isNotNull();

		var messages = Arrays.asList(new McpSchema.SamplingMessage(Role.USER, new McpSchema.TextContent("Test message")));
		var modelPrefs = new McpSchema.ModelPreferences(Collections.emptyList(), 1.0, 1.0, 1.0);

		var request = new CreateMessageRequest(messages, modelPrefs, null,
				CreateMessageRequest.ContextInclusionStrategy.NONE, null, 100, Collections.emptyList(),Collections.emptyMap());

		StepVerifier.create(mcpAsyncServer.createMessage(request)).verifyErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(McpError.class)
				.hasMessage("Client must be configured with sampling capabilities");
		});
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageSuccess(String clientType) throws InterruptedException {

		var clientBuilder = clientBulders.get(clientType);

		var mcpAsyncServer = McpServer.async(mcpServerTransport).serverInfo("test-server", "1.0.0").build();

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.getMessages()).hasSize(1);
			assertThat(request.getMessages().get(0).getContent()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		InitializeResult initResult = client.initialize();
		assertThat(initResult).isNotNull();

		var messages = Arrays.asList(new McpSchema.SamplingMessage(Role.USER, new McpSchema.TextContent("Test message")));
		var modelPrefs = new McpSchema.ModelPreferences(Collections.emptyList(), 1.0, 1.0, 1.0);

		var request = new CreateMessageRequest(messages, modelPrefs, null,
				CreateMessageRequest.ContextInclusionStrategy.NONE, null, 100, Collections.emptyList(),Collections.emptyMap());

		StepVerifier.create(mcpAsyncServer.createMessage(request)).consumeNextWith(result -> {
			assertThat(result).isNotNull();
			assertThat(result.getRole()).isEqualTo(Role.USER);
			assertThat(result.getContent()).isInstanceOf(McpSchema.TextContent.class);
			assertThat(((McpSchema.TextContent) result.getContent()).getText()).isEqualTo("Test message");
			assertThat(result.getModel()).isEqualTo("MockModelName");
			assertThat(result.getStopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
		}).verifyComplete();
	}

	// ---------------------------------------
	// Roots Tests
	// ---------------------------------------
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsSuccess(String clientType) {
		var clientBuilder = clientBulders.get(clientType);

		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"), new Root("uri2://", "root2"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransport)
			.rootsChangeConsumer(rootsUpdate -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(rootsRef.get()).isNull();

		assertThat(mcpServer.listRoots().getRoots()).containsAll(roots);

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(roots);
		});

		// Remove a root
		mcpClient.removeRoot(roots.get(0).getUri());

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Arrays.asList(roots.get(1)));
		});

		// Add a new root
		var root3 = new Root("uri3://", "root3");
		mcpClient.addRoot(root3);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Arrays.asList(roots.get(1), root3));
		});

		mcpClient.close();
		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsWithoutCapability(String clientType) {
		var clientBuilder = clientBulders.get(clientType);

		var mcpServer = McpServer.sync(mcpServerTransport).rootsChangeConsumer(rootsUpdate -> {
		}).build();

		// Create client without roots capability
		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()) // No
																							// roots
																							// capability
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		// Attempt to list roots should fail
		assertThatThrownBy(() -> mcpServer.listRoots().getRoots()).isInstanceOf(McpError.class)
			.hasMessage("Roots not supported");

		mcpClient.close();
		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsWithEmptyRootsList(String clientType) {
		var clientBuilder = clientBulders.get(clientType);

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransport)
			.rootsChangeConsumer(rootsUpdate -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(Collections.emptyList()) // Empty roots list
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).isEmpty();
		});

		mcpClient.close();
		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsWithMultipleConsumers(String clientType) {
		var clientBuilder = clientBulders.get(clientType);

		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransport)
			.rootsChangeConsumer(rootsUpdate -> rootsRef1.set(rootsUpdate))
			.rootsChangeConsumer(rootsUpdate -> rootsRef2.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef1.get()).containsAll(roots);
			assertThat(rootsRef2.get()).containsAll(roots);
		});

		mcpClient.close();
		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsServerCloseWithActiveSubscription(String clientType) {

		var clientBuilder = clientBulders.get(clientType);

		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransport)
			.rootsChangeConsumer(rootsUpdate -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(roots);
		});

		// Close server while subscription is active
		mcpServer.close();

		// Verify client can handle server closure gracefully
		mcpClient.close();
	}

	// ---------------------------------------
	// Tools Tests
	// ---------------------------------------

	String emptyJsonSchema = "{\n" +
			"    \"$schema\": \"http://json-schema.org/draft-07/schema#\",\n" +
			"    \"type\": \"object\",\n" +
			"    \"properties\": {}\n" +
			"}";

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolCallSuccess(String clientType) {

		var clientBuilder = clientBulders.get(clientType);

		var callResponse = new CallToolResult(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolRegistration tool1 = new McpServerFeatures.SyncToolRegistration(
				new Tool("tool1", "tool1 description", emptyJsonSchema), request -> {
					// perform a blocking call to a remote service
					var restTemplate = new RestTemplate();
					String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransport)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		var mcpClient = clientBuilder.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(mcpClient.listTools().getTools()).contains(tool1.getTool());

		CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1",Collections.emptyMap()));

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo(callResponse);

		mcpClient.close();
		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testToolListChangeHandlingSuccess(String clientType) {

		var clientBuilder = clientBulders.get(clientType);

		var callResponse = new CallToolResult(Collections.singletonList(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolRegistration tool1 = new McpServerFeatures.SyncToolRegistration(
				new Tool("tool1", "tool1 description", emptyJsonSchema), request -> {
					// perform a blocking call to a remote service
					var restTemplate = new RestTemplate();
					String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransport)
			.capabilities(ServerCapabilities.builder().tools(true).build())
			.tools(tool1)
			.build();

		AtomicReference<List<Tool>> rootsRef = new AtomicReference<>();
		var mcpClient = clientBuilder.toolsChangeConsumer(toolsUpdate -> {
			// perform a blocking call to a remote service
			var restTemplate = new RestTemplate();
			String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
			assertThat(response).isNotBlank();
			rootsRef.set(toolsUpdate);
		}).build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(rootsRef.get()).isNull();

		assertThat(mcpClient.listTools().getTools()).contains(tool1.getTool());

		mcpServer.notifyToolsListChanged();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Arrays.asList(tool1.getTool()));
		});

		// Remove a tool
		mcpServer.removeTool("tool1");

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).isEmpty();
		});

		// Add a new tool
		McpServerFeatures.SyncToolRegistration tool2 = new McpServerFeatures.SyncToolRegistration(
				new Tool("tool2", "tool2 description", emptyJsonSchema), request -> callResponse);

		mcpServer.addTool(tool2);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Arrays.asList(tool2.getTool()));
		});

		mcpClient.close();
		mcpServer.close();
	}

}
