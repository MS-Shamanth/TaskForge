package com.shamanth.taskforge.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "taskforge")
public record TaskForgeProperties(
		@DefaultValue Worker worker,
		@DefaultValue Retry retry,
		@DefaultValue Reaper reaper,
		@DefaultValue Report report,
		@DefaultValue Webhook webhook) {

	public record Worker(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("8") int threads,
			@DefaultValue("500ms") Duration pollInterval,
			@DefaultValue("30s") Duration lease) {
	}

	public record Retry(
			@DefaultValue("5") int defaultMaxAttempts,
			@DefaultValue("1s") Duration baseDelay,
			@DefaultValue("5m") Duration maxDelay,
			@DefaultValue("0.2") double jitterRatio) {
	}

	public record Reaper(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("10s") Duration interval) {
	}

	public record Report(
			@DefaultValue("reports") String dir) {
	}

	public record Webhook(
			@DefaultValue("5s") Duration connectTimeout,
			@DefaultValue("10s") Duration readTimeout) {
	}

}
