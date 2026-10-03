package com.shamanth.taskforge.worker;

import java.time.Duration;

import com.shamanth.taskforge.AbstractManualWorkerTest;
import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobStatus;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;

class LeaseRecoveryTest extends AbstractManualWorkerTest {

	private static final Duration SHORT_LEASE = Duration.ofMillis(200);

	@Autowired
	private LeaseReaper leaseReaper;

	@Autowired
	private WorkerFactory workerFactory;

	@Test
	void expiredLeaseIsRequeuedAndFinishedByAnotherWorker() {
		long id = jobRepository.insert("COUNTING", "{}", 0, null, 5, null).orElseThrow().id();
		// Simulated crash: the job is claimed but its worker never reports back.
		assertThat(jobRepository.claim("crashed-worker", 1, SHORT_LEASE)).hasSize(1);
		awaitLeaseExpired(id);

		assertThat(leaseReaper.reapExpiredLeases()).singleElement().satisfies((job) -> {
			assertThat(job.id()).isEqualTo(id);
			assertThat(job.status()).isEqualTo(JobStatus.RETRY);
			assertThat(job.lockedBy()).isNull();
			assertThat(job.lastError()).contains("crashed-worker");
		});

		Worker rescuer = workerFactory.create("rescue-worker", 2);
		try {
			assertThat(rescuer.pollOnce()).isEqualTo(1);
			Job done = awaitStatus(id, JobStatus.SUCCEEDED, Duration.ofSeconds(10));

			assertThat(done.attempts()).isEqualTo(2);
			assertThat(countingHandler.executionsOf(id)).isEqualTo(1);
			assertThat(countingHandler.executionsByWorker()).containsExactly(entry("rescue-worker", 1));
		}
		finally {
			rescuer.shutdown();
		}
	}

	@Test
	void expiredLeaseOnFinalAttemptIsDeadLettered() {
		long id = jobRepository.insert("COUNTING", "{}", 0, null, 1, null).orElseThrow().id();
		assertThat(jobRepository.claim("crashed-worker", 1, SHORT_LEASE)).hasSize(1);
		awaitLeaseExpired(id);

		assertThat(leaseReaper.reapExpiredLeases()).singleElement().satisfies((job) -> {
			assertThat(job.id()).isEqualTo(id);
			assertThat(job.status()).isEqualTo(JobStatus.DEAD);
			assertThat(job.lastError()).contains("Lease expired");
		});
		assertThat(jobRepository.claim("next-worker", 10, Duration.ofSeconds(30))).isEmpty();
	}

	private void awaitLeaseExpired(long id) {
		await().atMost(Duration.ofSeconds(5))
			.pollInterval(Duration.ofMillis(50))
			.until(() -> jdbc.queryForObject("SELECT locked_until < now() FROM jobs WHERE id = ?", Boolean.class, id));
	}

}
