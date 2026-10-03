package com.shamanth.taskforge;

import com.shamanth.taskforge.support.CountingJobHandler;
import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * "Manual" context: no application poller and no scheduled reaper, so tests drive claims, workers and the reaper
 * themselves. A separate context with its own PostgreSQL container, so the live poller never sees these rows.
 */
@TestPropertySource(properties = { "taskforge.worker.enabled=false", "taskforge.reaper.enabled=false" })
public abstract class AbstractManualWorkerTest extends AbstractIntegrationTest {

	@Autowired
	protected CountingJobHandler countingHandler;

	@BeforeEach
	void cleanJobs() {
		jdbc.update("DELETE FROM jobs");
		countingHandler.reset();
	}

}
