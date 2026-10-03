package com.shamanth.taskforge.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.awaitility.Awaitility.await;

/**
 * Drives {@link Worker}s like the scheduled poller does, but in tight loops (one per worker), until the expected
 * number of jobs is SUCCEEDED. Shuts the workers down afterwards and rethrows any poll-loop failure.
 */
final class PollLoops {

	private PollLoops() {
	}

	/** Returns the milliseconds from starting the loops until {@code expected} jobs were seen SUCCEEDED. */
	static long runUntilSucceeded(List<Worker> workers, JdbcTemplate jdbc, int expected, Duration timeout,
			Duration checkInterval) throws Exception {
		AtomicBoolean running = new AtomicBoolean(true);
		ExecutorService loops = Executors.newFixedThreadPool(workers.size());
		List<Future<?>> pollLoops = new ArrayList<>();
		long elapsedMs;
		long started = System.nanoTime();
		try {
			for (Worker worker : workers) {
				pollLoops.add(loops.submit(() -> {
					while (running.get()) {
						if (worker.pollOnce() == 0) {
							Thread.sleep(5);
						}
					}
					return null;
				}));
			}
			await().atMost(timeout)
				.pollInterval(checkInterval)
				.until(() -> jdbc.queryForObject("SELECT count(*) FROM jobs WHERE status = 'SUCCEEDED'", Long.class),
						(succeeded) -> succeeded == expected);
			elapsedMs = (System.nanoTime() - started) / 1_000_000;
		}
		finally {
			running.set(false);
			loops.shutdown();
			loops.awaitTermination(10, TimeUnit.SECONDS);
			workers.forEach(Worker::shutdown);
		}
		for (Future<?> pollLoop : pollLoops) {
			pollLoop.get();
		}
		return elapsedMs;
	}

}
