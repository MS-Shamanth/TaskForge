package com.shamanth.taskforge.api;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.shamanth.taskforge.AbstractIntegrationTest;
import com.shamanth.taskforge.job.JobStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST contract. Jobs that must stay QUEUED are scheduled an hour ahead so the live poller leaves them alone;
 * RUNNING, DEAD and SUCCEEDED rows are inserted directly.
 */
class JobApiTest extends AbstractIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void submitReturns201WithLocationAndDefaults() throws Exception {
		MvcResult result = submit(laterNotification("hello"), null).andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andExpect(jsonPath("$.type").value("NOTIFICATION"))
			.andExpect(jsonPath("$.payload.message").value("hello"))
			.andExpect(jsonPath("$.maxAttempts").value(5))
			.andExpect(jsonPath("$.priority").value(0))
			.andExpect(jsonPath("$.attempts").value(0))
			.andReturn();

		long id = idOf(result);
		assertThat(result.getResponse().getHeader("Location")).endsWith("/api/jobs/" + id);
	}

	@Test
	void sameIdempotencyKeyReplaysTheOriginalJobWith200() throws Exception {
		String key = "api-" + UUID.randomUUID();

		long first = idOf(submit(laterNotification("once"), key).andExpect(status().isCreated()).andReturn());
		long second = idOf(submit(laterNotification("once"), key).andExpect(status().isOk())
			.andExpect(jsonPath("$.idempotencyKey").value(key))
			.andReturn());

		assertThat(second).isEqualTo(first);
	}

	@Test
	void unknownTypeIsRejectedWith400() throws Exception {
		submit("{\"type\": \"SMS\", \"payload\": {}}", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.error").value("Bad Request"))
			.andExpect(jsonPath("$.path").value("/api/jobs"))
			.andExpect(jsonPath("$.message", containsString("Unknown job type 'SMS'")));
	}

	@Test
	void missingPayloadIsRejectedWith400AndFieldErrors() throws Exception {
		submit("{\"type\": \"NOTIFICATION\"}", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Validation failed"))
			.andExpect(jsonPath("$.fieldErrors.payload").exists());
	}

	@Test
	void malformedJsonIsRejectedWith400() throws Exception {
		submit("{\"type\": ", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Malformed JSON request body"));
	}

	@Test
	void invalidPayloadForTheTypeIsRejectedWith400() throws Exception {
		submit("{\"type\": \"WEBHOOK\", \"payload\": {\"url\": \"ftp://x\"}}", null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message", containsString("payload.url")));
	}

	@Test
	void typeIsCaseInsensitive() throws Exception {
		String runAt = Instant.now().plus(1, ChronoUnit.HOURS).toString();
		submit("{\"type\": \" notification \", \"payload\": {\"message\": \"hi\"}, \"runAt\": \"" + runAt + "\"}", null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("NOTIFICATION"));
	}

	@Test
	void maxAttemptsOutOfRangeIsRejectedWith400() throws Exception {
		submit("{\"type\": \"NOTIFICATION\", \"payload\": {\"message\": \"hi\"}, \"maxAttempts\": 0}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors.maxAttempts").exists());
		submit("{\"type\": \"NOTIFICATION\", \"payload\": {\"message\": \"hi\"}, \"maxAttempts\": 101}", null)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.fieldErrors.maxAttempts").exists());
	}

	@Test
	void invalidQueryParametersAreRejectedWith400() throws Exception {
		mockMvc.perform(get("/api/jobs").param("status", "NOPE"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Invalid value 'NOPE' for parameter 'status'"));
		mockMvc.perform(get("/api/jobs").param("size", "0"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message", containsString("size")));
		mockMvc.perform(get("/api/jobs/abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Invalid value 'abc' for parameter 'id'"));
	}

	@Test
	void unknownJobReturns404() throws Exception {
		mockMvc.perform(get("/api/jobs/999999999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(404))
			.andExpect(jsonPath("$.message").value("Job 999999999 not found"));
		mockMvc.perform(delete("/api/jobs/999999999"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void getsOneJobAndListsJobsByStatusWithPaging() throws Exception {
		long id = idOf(submit(laterNotification("first"), null).andReturn());
		submit(laterNotification("second"), null).andExpect(status().isCreated());
		submit(laterNotification("third"), null).andExpect(status().isCreated());

		mockMvc.perform(get("/api/jobs/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.payload.message").value("first"));
		mockMvc.perform(get("/api/jobs").param("status", "QUEUED").param("page", "0").param("size", "2"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[*].status", everyItem(is("QUEUED"))))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(2))
			.andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(3)))
			.andExpect(jsonPath("$.totalPages", greaterThanOrEqualTo(2)));
	}

	@Test
	void retryRequeuesDeadJobsOnly() throws Exception {
		long queued = idOf(submit(laterNotification("not dead"), null).andReturn());
		mockMvc.perform(post("/api/jobs/{id}/retry", queued))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.message", containsString("only DEAD jobs can be retried")));

		long dead = insertRow("INSERT INTO jobs (type, payload, status, attempts, last_error) "
				+ "VALUES ('COUNTING', '{}', 'DEAD', 5, 'boom') RETURNING id");
		mockMvc.perform(post("/api/jobs/{id}/retry", dead))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(dead))
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andExpect(jsonPath("$.attempts").value(0));
	}

	@Test
	void cancelWorksOnlyForQueuedOrRetryJobs() throws Exception {
		long queued = idOf(submit(laterNotification("cancel me"), null).andReturn());
		mockMvc.perform(delete("/api/jobs/{id}", queued))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("CANCELLED"));
		mockMvc.perform(delete("/api/jobs/{id}", queued))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message", containsString("is CANCELLED")));

		long running = insertRow("INSERT INTO jobs (type, payload, status, attempts, locked_by, locked_until) "
				+ "VALUES ('COUNTING', '{}', 'RUNNING', 1, 'other-worker', now() + interval '1 hour') RETURNING id");
		mockMvc.perform(delete("/api/jobs/{id}", running))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message", containsString("is RUNNING")));

		long succeeded = insertRow("INSERT INTO jobs (type, payload, status, attempts) "
				+ "VALUES ('COUNTING', '{}', 'SUCCEEDED', 1) RETURNING id");
		mockMvc.perform(delete("/api/jobs/{id}", succeeded))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message", containsString("is SUCCEEDED")));
	}

	@Test
	void statsReportsEveryStatusAndTheTotal() throws Exception {
		submit(laterNotification("counted"), null).andExpect(status().isCreated());

		MvcResult result = mockMvc.perform(get("/api/stats"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").isNumber())
			.andReturn();

		JsonNode stats = jsonMapper.readTree(result.getResponse().getContentAsString());
		long sum = 0;
		for (JobStatus status : JobStatus.values()) {
			JsonNode count = stats.path("counts").path(status.name());
			assertThat(count.isNumber()).as("count for %s", status).isTrue();
			sum += count.asLong();
		}
		assertThat(stats.get("total").asLong()).isEqualTo(sum).isPositive();
	}

	private ResultActions submit(String json, String idempotencyKey) throws Exception {
		var request = post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(json);
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey);
		}
		return mockMvc.perform(request);
	}

	private static String laterNotification(String message) {
		String runAt = Instant.now().plus(1, ChronoUnit.HOURS).toString();
		return "{\"type\": \"NOTIFICATION\", \"payload\": {\"message\": \"" + message + "\"}, \"runAt\": \"" + runAt
				+ "\"}";
	}

	private long insertRow(String insertReturningId) {
		Long id = jdbc.queryForObject(insertReturningId, Long.class);
		assertThat(id).isNotNull();
		return id;
	}

	private long idOf(MvcResult result) throws Exception {
		return jsonMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
	}

}
