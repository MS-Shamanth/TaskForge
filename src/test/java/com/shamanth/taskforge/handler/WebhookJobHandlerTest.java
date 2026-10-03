package com.shamanth.taskforge.handler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.shamanth.taskforge.AbstractIntegrationTest;
import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobService;
import com.shamanth.taskforge.job.JobStatus;
import com.shamanth.taskforge.job.NewJob;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookJobHandlerTest extends AbstractIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final List<String> receivedBodies = new CopyOnWriteArrayList<>();

	private static final List<String> receivedKeys = new CopyOnWriteArrayList<>();

	private static final AtomicInteger failHits = new AtomicInteger();

	private static HttpServer server;

	@Autowired
	private JobService jobService;

	@BeforeAll
	static void startReceiver() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ok", (exchange) -> {
			receivedBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			receivedKeys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
			exchange.sendResponseHeaders(200, -1);
			exchange.close();
		});
		server.createContext("/fail", (exchange) -> {
			failHits.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stopReceiver() {
		server.stop(0);
	}

	@BeforeEach
	void resetReceiver() {
		receivedBodies.clear();
		receivedKeys.clear();
		failHits.set(0);
	}

	@Test
	void deliversJsonBodyAndSucceedsOn2xx() {
		JsonNode payload = jsonMapper.readTree("{\"url\": \"" + url("/ok") + "\", "
				+ "\"body\": {\"event\": \"report.ready\", \"reportId\": 42}}");

		long id = jobService.submit(new NewJob("WEBHOOK", payload, null, null, null, null)).job().id();
		Job done = awaitStatus(id, JobStatus.SUCCEEDED, TIMEOUT);

		assertThat(done.attempts()).isEqualTo(1);
		assertThat(receivedBodies).hasSize(1);
		assertThat(jsonMapper.readTree(receivedBodies.get(0))).isEqualTo(payload.get("body"));
		assertThat(receivedKeys).containsExactly("taskforge-job-" + id);
	}

	@Test
	void non2xxIsRetriedThenDeadLettered() {
		JsonNode payload = jsonMapper.readTree("{\"url\": \"" + url("/fail") + "\"}");

		long id = jobService.submit(new NewJob("WEBHOOK", payload, null, null, 2, null)).job().id();
		Job dead = awaitStatus(id, JobStatus.DEAD, TIMEOUT);

		assertThat(dead.attempts()).isEqualTo(2);
		assertThat(dead.lastError()).contains("500");
		assertThat(failHits.get()).isEqualTo(2);
	}

	private static String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

}
