package com.shamanth.taskforge.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.shamanth.taskforge.AbstractIntegrationTest;
import com.shamanth.taskforge.job.JobService;
import com.shamanth.taskforge.job.NewJob;
import com.shamanth.taskforge.job.SubmitResult;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdempotencyTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JobService jobService;

	@Test
	void sameKeyTwiceCreatesOneRowAndReplaysWith200() throws Exception {
		String key = "idem-" + UUID.randomUUID();
		String body = "{\"type\": \"NOTIFICATION\", \"payload\": {\"message\": \"exactly one row\"}}";

		MvcResult first = mockMvc
			.perform(post("/api/jobs").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated())
			.andReturn();
		MvcResult second = mockMvc
			.perform(post("/api/jobs").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.idempotencyKey").value(key))
			.andReturn();

		assertThat(idOf(second)).isEqualTo(idOf(first));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE idempotency_key = ?", Long.class, key))
			.isEqualTo(1L);
	}

	@Test
	void concurrentSubmissionsWithSameKeyCreateOneRow() throws Exception {
		String key = "race-" + UUID.randomUUID();
		NewJob request = new NewJob("NOTIFICATION", jsonMapper.readTree("{\"message\": \"race\"}"), null,
				Instant.now().plus(1, ChronoUnit.HOURS), null, key);
		int threads = 8;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<SubmitResult> results = new ArrayList<>();
		try {
			List<Future<SubmitResult>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return jobService.submit(request);
				}));
			}
			start.countDown();
			for (Future<SubmitResult> future : futures) {
				results.add(future.get(10, TimeUnit.SECONDS));
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(results).hasSize(threads);
		assertThat(results).filteredOn(SubmitResult::created).hasSize(1);
		assertThat(results).extracting((result) -> result.job().id()).containsOnly(results.get(0).job().id());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs WHERE idempotency_key = ?", Long.class, key))
			.isEqualTo(1L);
	}

	private long idOf(MvcResult result) throws Exception {
		return jsonMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
	}

}
