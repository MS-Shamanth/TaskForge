package com.shamanth.taskforge.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.shamanth.taskforge.AbstractManualWorkerTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Throughput with 1 vs 2 worker pools (8 threads each) on jobs that each take about 5 ms. Skipped by default; run
 * with {@code .\mvnw.cmd test "-Dtest=ThroughputBenchmarkTest" "-Dtaskforge.benchmark=true"}.
 */
@Tag("benchmark")
@EnabledIfSystemProperty(named = "taskforge.benchmark", matches = "true")
class ThroughputBenchmarkTest extends AbstractManualWorkerTest {

	private static final int JOBS = 2_000;

	private static final int THREADS_PER_WORKER = 8;

	private static final int SLEEP_MS = 5;

	@Autowired
	private WorkerFactory workerFactory;

	@Test
	void throughputWithOneAndTwoWorkerPools() throws Exception {
		for (int pools : new int[] { 1, 2 }) {
			jdbc.update("DELETE FROM jobs");
			countingHandler.reset();
			jdbc.update("INSERT INTO jobs (type, payload) SELECT 'COUNTING', jsonb_build_object('sleepMs', ?) "
					+ "FROM generate_series(1, ?)", SLEEP_MS, JOBS);

			List<Worker> workers = new ArrayList<>();
			for (int i = 0; i < pools; i++) {
				workers.add(workerFactory.create("bench-" + pools + "-" + i, THREADS_PER_WORKER));
			}
			long elapsedMs = PollLoops.runUntilSucceeded(workers, jdbc, JOBS, Duration.ofMinutes(2),
					Duration.ofMillis(10));
			long jobsPerSec = Math.round(JOBS * 1000.0 / Math.max(1, elapsedMs));

			System.out.printf("BENCHMARK pools=%d threadsPerWorker=%d jobs=%d sleepMs=%d elapsedMs=%d jobsPerSec=%d%n",
					pools, THREADS_PER_WORKER, JOBS, SLEEP_MS, elapsedMs, jobsPerSec);
			assertThat(countingHandler.executionsByJob()).hasSize(JOBS)
				.allSatisfy((id, count) -> assertThat(count).isEqualTo(1));
		}
	}

}
