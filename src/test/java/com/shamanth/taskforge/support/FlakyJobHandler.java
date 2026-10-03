package com.shamanth.taskforge.support;

import com.shamanth.taskforge.handler.JobHandler;
import com.shamanth.taskforge.job.Job;

/**
 * Fails while {@code attempts <= payload.failTimes}, then succeeds.
 */
public class FlakyJobHandler implements JobHandler {

	@Override
	public String type() {
		return "FLAKY";
	}

	@Override
	public void handle(Job job) {
		int failTimes = job.payload().path("failTimes").asInt(0);
		if (job.attempts() <= failTimes) {
			throw new IllegalStateException("Simulated failure " + job.attempts());
		}
	}

}
