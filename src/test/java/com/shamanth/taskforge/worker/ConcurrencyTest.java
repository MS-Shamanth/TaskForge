package com.shamanth.taskforge.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;

import com.shamanth.taskforge.AbstractManualWorkerTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 4 independent {@link Worker}s with 8 threads each (32 worker threads) race for the same 5,000 rows through the
 * SKIP LOCKED claim query. Every job must run exactly once and end SUCCEEDED.
 */
class ConcurrencyTest extends AbstractManualWorkerTest {

	private static final int JOBS = 5_000;

	private static final int WORKERS = 4;

	private static final int THREADS_PER_WORKER = 8;

	@Autowired
	private WorkerFactory workerFactory;

	@Test
	void fourWorkersWithEightThreadsRunEveryJobExactlyOnce() throws Exception {
		jdbc.update("INSERT INTO jobs (type, payload) SELECT 'COUNTING', jsonb_build_object('n', g) "
				+ "FROM generate_series(1, ?) g", JOBS);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM jobs", Long.class)).isEqualTo(JOBS);

		List<Worker> workers = new ArrayList<>();
		for (int i = 0; i < WORKERS; i++) {
			workers.add(workerFactory.create("worker-" + i, THREADS_PER_WORKER));
		}
		long elapsedMs = PollLoops.runUntilSucceeded(workers, jdbc, JOBS, Duration.ofMinutes(2),
				Duration.ofMillis(100));

		List<Long> ids = jdbc.queryForList("SELECT id FROM jobs", Long.class);
		Map<Long, Integer> executions = countingHandler.executionsByJob();
		List<Long> notExactlyOnce = ids.stream().filter((id) -> executions.getOrDefault(id, 0) != 1).toList();
		IntSummaryStatistics perJob = executions.values().stream().mapToInt(Integer::intValue).summaryStatistics();
		long duplicates = executions.values().stream().filter((count) -> count > 1).count();
		Map<String, Integer> perWorker = countingHandler.executionsByWorker();
		int distinctThreads = countingHandler.threadNames().size();
		long jobsPerSec = Math.round(JOBS * 1000.0 / Math.max(1, elapsedMs));

		System.out.printf("CONCURRENCY jobs=%d workers=%d threadsPerWorker=%d totalThreads=%d elapsedMs=%d "
				+ "jobsPerSec=%d executions[min=%d,max=%d,total=%d] duplicates=%d perWorker=%s distinctThreads=%d%n",
				JOBS, WORKERS, THREADS_PER_WORKER, WORKERS * THREADS_PER_WORKER, elapsedMs, jobsPerSec,
				perJob.getMin(), perJob.getMax(), perJob.getSum(), duplicates, perWorker, distinctThreads);

		assertThat(ids).hasSize(JOBS);
		assertThat(notExactlyOnce).as("jobs not executed exactly once").isEmpty();
		assertThat(executions).hasSize(JOBS);
		assertThat(perJob.getSum()).isEqualTo(JOBS);
		assertThat(duplicates).isZero();

		Map<String, Object> db = jdbc.queryForMap("SELECT count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded, "
				+ "max(attempts) AS max_attempts FROM jobs");
		assertThat(((Number) db.get("succeeded")).longValue()).isEqualTo(JOBS);
		assertThat(((Number) db.get("max_attempts")).intValue()).isEqualTo(1);

		assertThat(perWorker).hasSize(WORKERS)
			.allSatisfy((worker, count) -> assertThat(count).isGreaterThanOrEqualTo(THREADS_PER_WORKER));
		assertThat(distinctThreads).isEqualTo(WORKERS * THREADS_PER_WORKER);
	}

}
