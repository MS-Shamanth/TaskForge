package com.shamanth.taskforge.stream;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.shamanth.taskforge.AbstractIntegrationTest;
import com.shamanth.taskforge.job.JobService;
import com.shamanth.taskforge.job.JobStatus;
import com.shamanth.taskforge.job.NewJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class JobStatusWebSocketTest extends AbstractIntegrationTest {

	/** No real job has this id (ids come from a sequence starting at 1). */
	private static final long FORGED_JOB_ID = -1;

	@LocalServerPort
	private int port;

	@Autowired
	private SimpMessagingTemplate messagingTemplate;

	@Autowired
	private JobService jobService;

	@Test
	void stompSubscriberReceivesStatusChangesAndNotifications() throws Exception {
		List<JsonNode> jobEvents = new CopyOnWriteArrayList<>();
		List<JsonNode> notifications = new CopyOnWriteArrayList<>();
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		client.setDefaultHeartbeat(new long[] { 0, 0 });
		StompSession session = client
			.connectAsync(wsUrl(), new StompSessionHandlerAdapter() {
			})
			.get(5, TimeUnit.SECONDS);
		try {
			session.subscribe(WebSocketPublisher.JOBS_TOPIC, collectInto(jobEvents));
			session.subscribe(WebSocketPublisher.NOTIFICATIONS_TOPIC, collectInto(notifications));
			// SUBSCRIBE frames are processed asynchronously: probe both topics until each subscription is live.
			Object probe = Map.of("probe", true);
			await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).until(() -> {
				messagingTemplate.convertAndSend(WebSocketPublisher.JOBS_TOPIC, probe);
				messagingTemplate.convertAndSend(WebSocketPublisher.NOTIFICATIONS_TOPIC, probe);
				return hasProbe(jobEvents) && hasProbe(notifications);
			});

			JsonNode payload = jsonMapper.readTree("{\"message\": \"hello websocket\"}");
			long id = jobService.submit(new NewJob("NOTIFICATION", payload, null, null, null, null)).job().id();

			await().atMost(Duration.ofSeconds(10)).until(() -> statusesOf(jobEvents, id).contains("SUCCEEDED"));
			List<String> statuses = statusesOf(jobEvents, id);
			assertThat(statuses).contains("QUEUED", "RUNNING", "SUCCEEDED");
			assertThat(statuses.indexOf("RUNNING")).isLessThan(statuses.indexOf("SUCCEEDED"));

			JsonNode succeeded = eventsOf(jobEvents, id).stream()
				.filter((event) -> "SUCCEEDED".equals(event.path("status").asString()))
				.findFirst()
				.orElseThrow();
			assertThat(succeeded.path("type").asString()).isEqualTo("NOTIFICATION");
			assertThat(succeeded.path("attempts").asInt()).isEqualTo(1);
			assertThat(succeeded.path("timestamp").isString()).isTrue();

			await().atMost(Duration.ofSeconds(5))
				.until(() -> notifications.stream().anyMatch((n) -> n.path("jobId").asLong() == id));
			JsonNode notification = notifications.stream()
				.filter((n) -> n.path("jobId").asLong() == id)
				.findFirst()
				.orElseThrow();
			assertThat(notification.path("message").asString()).isEqualTo("hello websocket");
		}
		finally {
			session.disconnect();
			client.stop();
		}
	}

	/*
	 * The simple broker relays every client message of type MESSAGE, and two client commands have that type: SEND, and
	 * MESSAGE (normally a server frame). StompSession can only send SEND, so the forger writes raw STOMP frames over a
	 * plain WebSocket, as any non-Spring client can.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "SEND", "MESSAGE" })
	void clientCannotPublishToTopics(String command) throws Exception {
		List<JsonNode> received = new CopyOnWriteArrayList<>();
		BlockingQueue<String> forgerFrames = new LinkedBlockingQueue<>();
		CompletableFuture<CloseStatus> forgerClosed = new CompletableFuture<>();
		StandardWebSocketClient webSocketClient = new StandardWebSocketClient();
		WebSocketStompClient client = new WebSocketStompClient(webSocketClient);
		client.setDefaultHeartbeat(new long[] { 0, 0 });
		// Two separate WebSocket connections: a subscriber, and a client that tries to publish a forged event.
		StompSession subscriber = client
			.connectAsync(wsUrl(), new StompSessionHandlerAdapter() {
			})
			.get(5, TimeUnit.SECONDS);
		WebSocketSession forger = webSocketClient.execute(new TextWebSocketHandler() {

			@Override
			protected void handleTextMessage(WebSocketSession session, TextMessage message) {
				forgerFrames.add(message.getPayload());
			}

			@Override
			public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
				forgerClosed.complete(status);
			}

		}, wsUrl()).get(5, TimeUnit.SECONDS);
		try {
			subscriber.subscribe(WebSocketPublisher.JOBS_TOPIC, collectInto(received));
			Object probe = Map.of("probe", true);
			await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).until(() -> {
				messagingTemplate.convertAndSend(WebSocketPublisher.JOBS_TOPIC, probe);
				return hasProbe(received);
			});

			forger.sendMessage(stompFrame("CONNECT", "accept-version:1.2\nheart-beat:0,0", ""));
			assertThat(forgerFrames.poll(5, TimeUnit.SECONDS)).startsWith("CONNECTED\n");
			JobStatusEvent forged = new JobStatusEvent(FORGED_JOB_ID, "REPORT", JobStatus.SUCCEEDED, 1, Instant.now());
			forger.sendMessage(stompFrame(command,
					"destination:" + WebSocketPublisher.JOBS_TOPIC + "\ncontent-type:application/json",
					jsonMapper.writeValueAsString(forged)));

			// Without the server's inbound interceptor, the broker relays the forged event to the subscriber.
			await().atMost(Duration.ofSeconds(5))
				.until(() -> !forgerFrames.isEmpty() || !eventsOf(received, FORGED_JOB_ID).isEmpty());
			assertThat(eventsOf(received, FORGED_JOB_ID)).as("forged events delivered to the subscriber").isEmpty();
			assertThat(forgerFrames.poll()).startsWith("ERROR\n").contains("server-to-client only");
			// After an ERROR frame the server closes that client's connection as a protocol error (1002).
			assertThat(forgerClosed.get(5, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.PROTOCOL_ERROR.getCode());

			// The subscriber is unaffected: a later server-side event still arrives, the forged one never does.
			Object marker = Map.of("marker", true);
			messagingTemplate.convertAndSend(WebSocketPublisher.JOBS_TOPIC, marker);
			await().atMost(Duration.ofSeconds(5)).until(() -> received.stream().anyMatch((e) -> e.has("marker")));
			assertThat(eventsOf(received, FORGED_JOB_ID)).isEmpty();
		}
		finally {
			if (forger.isOpen()) {
				forger.close();
			}
			subscriber.disconnect();
			client.stop();
		}
	}

	/** A raw STOMP 1.2 frame: the command, header lines, a blank line, the body and a NUL byte. */
	private static TextMessage stompFrame(String command, String headers, String body) {
		return new TextMessage(command + "\n" + headers + "\n\n" + body + "\0");
	}

	/** The server binds to 127.0.0.1 (server.address), so connect there, not to whatever localhost resolves to. */
	private String wsUrl() {
		return "ws://127.0.0.1:" + port + "/ws";
	}

	private StompFrameHandler collectInto(List<JsonNode> sink) {
		return new StompFrameHandler() {

			@Override
			public Type getPayloadType(StompHeaders headers) {
				return byte[].class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				sink.add(jsonMapper.readTree((byte[]) payload));
			}

		};
	}

	private static boolean hasProbe(List<JsonNode> events) {
		return events.stream().anyMatch((event) -> event.has("probe"));
	}

	private static List<JsonNode> eventsOf(List<JsonNode> events, long id) {
		return events.stream().filter((event) -> event.has("id") && event.get("id").asLong() == id).toList();
	}

	private static List<String> statusesOf(List<JsonNode> events, long id) {
		return eventsOf(events, id).stream().map((event) -> event.path("status").asString()).toList();
	}

}
