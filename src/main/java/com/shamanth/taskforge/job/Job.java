package com.shamanth.taskforge.job;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record Job(
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
}
