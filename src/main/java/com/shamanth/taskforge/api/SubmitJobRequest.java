package com.shamanth.taskforge.api;

import java.time.Instant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record SubmitJobRequest(
		@NotBlank String type,
		@NotNull JsonNode payload,
		Integer priority,
		Instant runAt,
		@Min(1) @Max(100) Integer maxAttempts) {
}
