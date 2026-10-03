package com.shamanth.taskforge.stream;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.shamanth.taskforge.AbstractIntegrationTest;
import com.shamanth.taskforge.job.JobService;
import com.shamanth.taskforge.job.NewJob;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class JobStatusWebSocketTest extends AbstractIntegrationTest {

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
			.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
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
