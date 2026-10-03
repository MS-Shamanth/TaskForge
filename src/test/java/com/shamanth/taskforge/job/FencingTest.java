package com.shamanth.taskforge.job;

import java.time.Duration;
import java.util.List;

import com.shamanth.taskforge.AbstractManualWorkerTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class FencingTest extends AbstractManualWorkerTest {

	private static final Duration SHORT_LEASE = Duration.ofMillis(200);

	private static final Duration LONG_LEASE = Duration.ofSeconds(30);

	@Test
	void staleWorkerCompletionIsRejected() {
		long id = insertJob();
		Job first = claimOne("worker-a", SHORT_LEASE);
		expireAndReap(id);
		Job second = claimOne("worker-b", LONG_LEASE);
		assertThat(second.attempts()).isEqualTo(2);

		assertThat(jobRepository.markSucceeded(id, "worker-a", first.attempts())).isEmpty();
		assertThat(jobRepository.markRetry(id, "worker-a", first.attempts(), Duration.ofSeconds(1), "late")).isEmpty();
		assertThat(jobRepository.markDead(id, "worker-a", first.attempts(), "late")).isEmpty();

		Job current = jobRepository.findById(id).orElseThrow();
		assertThat(current.status()).isEqualTo(JobStatus.RUNNING);
		assertThat(current.lockedBy()).isEqualTo("worker-b");
		assertThat(current.attempts()).isEqualTo(2);

		assertThat(jobRepository.markSucceeded(id, "worker-b", second.attempts())).get()
			.extracting(Job::status)
			.isEqualTo(JobStatus.SUCCEEDED);
	}

	@Test
	void staleAttemptFromSameWorkerIsRejected() {
		long id = insertJob();
		Job first = claimOne("worker-a", SHORT_LEASE);
		expireAndReap(id);
		Job second = claimOne("worker-a", LONG_LEASE);
		assertThat(second.attempts()).isEqualTo(2);

		assertThat(jobRepository.markSucceeded(id, "worker-a", first.attempts())).isEmpty();
		assertThat(jobRepository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.RUNNING);

		assertThat(jobRepository.markSucceeded(id, "worker-a", second.attempts())).get()
			.extracting(Job::status)
			.isEqualTo(JobStatus.SUCCEEDED);
	}

	private long insertJob() {
		return jobRepository.insert("COUNTING", "{}", 0, null, 5, null).orElseThrow().id();
	}

	private Job claimOne(String workerId, Duration lease) {
		List<Job> claimed = jobRepository.claim(workerId, 1, lease);
		assertThat(claimed).hasSize(1);
		return claimed.get(0);
	}

	private void expireAndReap(long id) {
		await().atMost(Duration.ofSeconds(5))
			.pollInterval(Duration.ofMillis(50))
			.until(() -> jdbc.queryForObject("SELECT locked_until < now() FROM jobs WHERE id = ?", Boolean.class, id));
		assertThat(jobRepository.reapExpiredLeases()).singleElement().satisfies((job) -> {
			assertThat(job.id()).isEqualTo(id);
			assertThat(job.status()).isEqualTo(JobStatus.RETRY);
			assertThat(job.lockedBy()).isNull();
		});
	}

}
