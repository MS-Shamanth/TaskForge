package com.shamanth.taskforge.worker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.shamanth.taskforge.AbstractIntegrationTest;
import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobService;
import com.shamanth.taskforge.job.JobStatus;
import com.shamanth.taskforge.job.NewJob;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class JobLifecycleTest extends AbstractIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Autowired
	private JobService jobService;

	@Test
	void reportJobSucceedsAndWritesCsv() throws Exception {
		Job submitted = submit("REPORT", """
				{"name": "lifecycle-report", "columns": ["region", "amount"],
				 "rows": [["north", 120], ["south, east", 95]]}
				""");

		Job done = awaitStatus(submitted.id(), JobStatus.SUCCEEDED, TIMEOUT);

		assertThat(done.attempts()).isEqualTo(1);
		assertThat(done.lastError()).isNull();
		Path csv = Path.of("target", "test-reports", "lifecycle-report-" + done.id() + ".csv");
		assertThat(Files.readAllLines(csv)).containsExactly("region,amount", "north,120", "\"south, east\",95");
	}

	@Test
	void flakyJobSucceedsOnThirdAttempt() {
		Job submitted = submit("FLAKY", "{\"failTimes\": 2}");

		Job done = awaitStatus(submitted.id(), JobStatus.SUCCEEDED, TIMEOUT);

		assertThat(done.attempts()).isEqualTo(3);
		assertThat(done.lastError()).contains("Simulated failure 2");
	}

	@Test
	void alwaysFailingJobIsDeadLetteredAfterFiveAttempts() {
		Job submitted = submit("ALWAYS_FAIL", "{}");
		assertThat(submitted.maxAttempts()).isEqualTo(5);

		Job dead = awaitStatus(submitted.id(), JobStatus.DEAD, TIMEOUT);

		assertThat(dead.maxAttempts()).isEqualTo(5);
		assertThat(dead.attempts()).isEqualTo(5);
		assertThat(dead.lastError()).contains("boom");
	}

	private Job submit(String type, String payloadJson) {
		JsonNode payload = jsonMapper.readTree(payloadJson);
		return jobService.submit(new NewJob(type, payload, null, null, null, null)).job();
	}

}
