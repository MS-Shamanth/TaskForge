package com.shamanth.taskforge.job;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

public record NewJob(
		String type,
		JsonNode payload,
		Integer priority,
		Instant runAt,
		Integer maxAttempts,
		String idempotencyKey) {
}
