package com.shamanth.taskforge.api;

import java.time.Instant;

import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobStatus;
import tools.jackson.databind.JsonNode;

public record JobResponse(
		long id,
		String type,
		JsonNode payload,
		JobStatus status,
		int priority,
		Instant runAt,
		int attempts,
		int maxAttempts,
		String lockedBy,
		Instant lockedUntil,
		String idempotencyKey,
		String lastError,
		Instant createdAt,
		Instant updatedAt) {

	public static JobResponse from(Job job) {
		return new JobResponse(job.id(), job.type(), job.payload(), job.status(), job.priority(), job.runAt(),
				job.attempts(), job.maxAttempts(), job.lockedBy(), job.lockedUntil(), job.idempotencyKey(),
				job.lastError(), job.createdAt(), job.updatedAt());
	}

}
