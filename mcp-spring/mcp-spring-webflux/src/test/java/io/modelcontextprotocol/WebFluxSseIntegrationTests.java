/*
 * Copyright 2024 - 2024 the original author or authors.
 */
package io.modelcontextprotocol;

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
import io.modelcontextprotocol.server.transport.WebFluxSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageRequest;
import io.modelcontextprotocol.spec.McpSchema.CreateMessageResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ModelPreferences;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.Root;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.var;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import org.springframework.http.server.reactive.HttpHandler;
import org.springframework.http.server.reactive.ReactorHttpHandlerAdapter;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunctions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;

public class WebFluxSseIntegrationTests {

	private static final int PORT = 8182;

	private static final String MESSAGE_ENDPOINT = "/mcp/message";

	private DisposableServer httpServer;

	private WebFluxSseServerTransportProvider mcpServerTransportProvider;

	ConcurrentHashMap<String, McpClient.SyncSpec> clientBulders = new ConcurrentHashMap<>();

	@BeforeEach
	public void before() {

		this.mcpServerTransportProvider = new WebFluxSseServerTransportProvider(new ObjectMapper(), MESSAGE_ENDPOINT);

		HttpHandler httpHandler = RouterFunctions.toHttpHandler(mcpServerTransportProvider.getRouterFunction());
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
	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageWithoutSamplingCapabilities(String clientType) {

		var clientBuilder = clientBulders.get(clientType);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.createMessage(mock(McpSchema.CreateMessageRequest.class)).block();

					return Mono.just(mock(CallToolResult.class));
				});

		McpServer.async(mcpServerTransportProvider).serverInfo("test-server", "1.0.0").tools(tool).build();

		// Create client without sampling capabilities
		var client = clientBuilder.clientInfo(new McpSchema.Implementation("Sample " + "client", "0.0.0")).build();

		assertThat(client.initialize()).isNotNull();

		try {
			client.callTool(new McpSchema.CallToolRequest("tool1", Collections.emptyMap()));
		}
		catch (McpError e) {
			assertThat(e).isInstanceOf(McpError.class)
				.hasMessage("Client must be configured with sampling capabilities");
		}
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testCreateMessageSuccess(String clientType) throws InterruptedException {

		// Client
		var clientBuilder = clientBulders.get(clientType);

		Function<CreateMessageRequest, CreateMessageResult> samplingHandler = request -> {
			assertThat(request.getMessages()).hasSize(1);
			assertThat(request.getMessages().get(0).getContent()).isInstanceOf(McpSchema.TextContent.class);

			return new CreateMessageResult(Role.USER, new McpSchema.TextContent("Test message"), "MockModelName",
					CreateMessageResult.StopReason.STOP_SEQUENCE);
		};

		var mcpClient = clientBuilder.clientInfo(new McpSchema.Implementation("Sample client", "0.0.0"))
			.capabilities(ClientCapabilities.builder().sampling().build())
			.sampling(samplingHandler)
			.build();

		// Server

		CallToolResult callResponse = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent("CALL RESPONSE")),
				null);

		McpServerFeatures.AsyncToolSpecification tool = new McpServerFeatures.AsyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					var craeteMessageRequest = McpSchema.CreateMessageRequest.builder()
						.messages(Arrays.asList(new McpSchema.SamplingMessage(McpSchema.Role.USER,
								new McpSchema.TextContent("Test message"))))
						.modelPreferences(ModelPreferences.builder()
							.hints(Collections.emptyList())
							.costPriority(1.0)
							.speedPriority(1.0)
							.intelligencePriority(1.0)
							.build())
						.build();

					StepVerifier.create(exchange.createMessage(craeteMessageRequest)).consumeNextWith(result -> {
						assertThat(result).isNotNull();
						assertThat(result.getRole()).isEqualTo(Role.USER);
						assertThat(result.getContent()).isInstanceOf(McpSchema.TextContent.class);
						assertThat(((McpSchema.TextContent) result.getContent()).getText()).isEqualTo("Test message");
						assertThat(result.getModel()).isEqualTo("MockModelName");
						assertThat(result.getStopReason()).isEqualTo(CreateMessageResult.StopReason.STOP_SEQUENCE);
					}).verifyComplete();

					return Mono.just(callResponse);
				});

		var mcpServer = McpServer.async(mcpServerTransportProvider)
			.serverInfo("test-server", "1.0.0")
			.tools(tool)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		CallToolResult response = mcpClient.callTool(new McpSchema.CallToolRequest("tool1",Collections.emptyMap()));

		assertThat(response).isNotNull();
		assertThat(response).isEqualTo(callResponse);

		mcpClient.close();
		mcpServer.close();
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
		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
			.build();

		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().roots(true).build())
			.roots(roots)
			.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		assertThat(rootsRef.get()).isNull();

		mcpClient.rootsListChangedNotification();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(roots);
		});

		// Remove a root
		mcpClient.removeRoot(roots.get(0).getUri());

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Collections.singletonList(roots.get(1)));
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

		McpServerFeatures.SyncToolSpecification tool = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {

					exchange.listRoots(); // try to list roots

					return mock(CallToolResult.class);
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider).rootsChangeHandler((exchange, rootsUpdate) -> {
		}).tools(tool).build();

		// Create client without roots capability
		// No roots capability
		var mcpClient = clientBuilder.capabilities(ClientCapabilities.builder().build()).build();

		assertThat(mcpClient.initialize()).isNotNull();

		// Attempt to list roots should fail
		try {
			mcpClient.callTool(new McpSchema.CallToolRequest("tool1",Collections.emptyMap()));
		}
		catch (McpError e) {
			assertThat(e).isInstanceOf(McpError.class).hasMessage("Roots not supported");
		}

		mcpClient.close();
		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testRootsNotifciationWithEmptyRootsList(String clientType) {
		var clientBuilder = clientBulders.get(clientType);

		AtomicReference<List<Root>> rootsRef = new AtomicReference<>();
		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
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
	void testRootsWithMultipleHandlers(String clientType) {
		var clientBuilder = clientBulders.get(clientType);

		List<Root> roots = Arrays.asList(new Root("uri1://", "root1"));

		AtomicReference<List<Root>> rootsRef1 = new AtomicReference<>();
		AtomicReference<List<Root>> rootsRef2 = new AtomicReference<>();

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef1.set(rootsUpdate))
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef2.set(rootsUpdate))
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
		var mcpServer = McpServer.sync(mcpServerTransportProvider)
			.rootsChangeHandler((exchange, rootsUpdate) -> rootsRef.set(rootsUpdate))
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

		var callResponse = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					var restTemplate = new RestTemplate();
					String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
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

		var callResponse = new McpSchema.CallToolResult(Arrays.asList(new McpSchema.TextContent("CALL RESPONSE")), null);
		McpServerFeatures.SyncToolSpecification tool1 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool1", "tool1 description", emptyJsonSchema), (exchange, request) -> {
					// perform a blocking call to a remote service
					var restTemplate = new RestTemplate();
					String response = restTemplate.getForObject("https://github.com/modelcontextprotocol/specification/blob/main/README.md", String.class);
					assertThat(response).isNotBlank();
					return callResponse;
				});

		var mcpServer = McpServer.sync(mcpServerTransportProvider)
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
		McpServerFeatures.SyncToolSpecification tool2 = new McpServerFeatures.SyncToolSpecification(
				new McpSchema.Tool("tool2", "tool2 description", emptyJsonSchema), (exchange, request) -> callResponse);

		mcpServer.addTool(tool2);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(rootsRef.get()).containsAll(Arrays.asList(tool2.getTool()));
		});

		mcpClient.close();
		mcpServer.close();
	}

	@ParameterizedTest(name = "{0} : {displayName} ")
	@ValueSource(strings = { "httpclient", "webflux" })
	void testInitialize(String clientType) {

		var clientBuilder = clientBulders.get(clientType);

		var mcpServer = McpServer.sync(mcpServerTransportProvider).build();

		var mcpClient = clientBuilder.build();

		InitializeResult initResult = mcpClient.initialize();
		assertThat(initResult).isNotNull();

		mcpClient.close();
		mcpServer.close();
	}

}
