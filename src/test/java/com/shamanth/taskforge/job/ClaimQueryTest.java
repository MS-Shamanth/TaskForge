package com.shamanth.taskforge.job;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import javax.sql.DataSource;

import com.shamanth.taskforge.AbstractManualWorkerTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class ClaimQueryTest extends AbstractManualWorkerTest {

	private static final Duration LEASE = Duration.ofSeconds(30);

	@Autowired
	private DataSource dataSource;

	@Test
	void claimsHigherPriorityFirst() {
		long low = insert(0, null);
		long high = insert(10, null);

		assertThat(claimIds("w1", 1)).containsExactly(high);
		assertThat(claimIds("w1", 1)).containsExactly(low);
	}

	@Test
	void samePriorityClaimsEarlierRunAtFirst() {
		Instant now = Instant.now();
		insert(0, now.minus(1, ChronoUnit.HOURS));
		long older = insert(0, now.minus(2, ChronoUnit.HOURS));

		assertThat(claimIds("w1", 1)).containsExactly(older);
	}

	@Test
	void doesNotClaimJobsScheduledInTheFuture() {
		long future = insert(0, Instant.now().plus(1, ChronoUnit.HOURS));
		long due = insert(0, null);

		assertThat(claimIds("w1", 10)).containsExactly(due);

		jdbc.update("UPDATE jobs SET run_at = now() - interval '1 second' WHERE id = ?", future);
		assertThat(claimIds("w1", 10)).containsExactly(future);
	}

	@Test
	void claimSetsRunningLeaseAndAttempt() {
		long id = insert(0, null);

		List<Job> claimed = jobRepository.claim("w1", 1, LEASE);

		assertThat(claimed).hasSize(1);
		Job job = claimed.get(0);
		assertThat(job.id()).isEqualTo(id);
		assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
		assertThat(job.lockedBy()).isEqualTo("w1");
		assertThat(job.attempts()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT locked_until > now() FROM jobs WHERE id = ?", Boolean.class, id))
			.isTrue();
	}

	@Test
	void claimRespectsLimit() {
		for (int i = 0; i < 5; i++) {
			insert(0, null);
		}

		assertThat(claimIds("w1", 3)).hasSize(3);
		assertThat(claimIds("w1", 3)).hasSize(2);
		assertThat(claimIds("w1", 3)).isEmpty();
	}

	@Test
	void skipsRowsLockedByAnotherTransaction() throws Exception {
		long locked = insert(0, null);
		long free = insert(0, null);

		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM jobs WHERE id = ? FOR UPDATE")) {
				lock.setLong(1, locked);
				lock.executeQuery().close();
			}

			long started = System.nanoTime();
			List<Long> claimed = claimIds("w2", 10);
			Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

			assertThat(claimed).containsExactly(free);
			assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
			connection.rollback();
		}

		assertThat(claimIds("w2", 10)).containsExactly(locked);
	}

	private long insert(int priority, Instant runAt) {
		return jobRepository.insert("COUNTING", "{}", priority, runAt, 5, null).orElseThrow().id();
	}

	private List<Long> claimIds(String workerId, int limit) {
		return jobRepository.claim(workerId, limit, LEASE).stream().map(Job::id).toList();
	}

}
