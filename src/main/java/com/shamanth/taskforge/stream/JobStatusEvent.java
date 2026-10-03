package com.shamanth.taskforge.stream;

import java.time.Instant;

import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobStatus;

public record JobStatusEvent(long id, String type, JobStatus status, int attempts, Instant timestamp) {

	public static JobStatusEvent from(Job job) {
		return new JobStatusEvent(job.id(), job.type(), job.status(), job.attempts(), job.updatedAt());
	}

}
