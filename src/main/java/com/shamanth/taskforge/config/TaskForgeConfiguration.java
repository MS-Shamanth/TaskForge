package com.shamanth.taskforge.config;

import com.shamanth.taskforge.worker.BackoffPolicy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TaskForgeConfiguration {

	@Bean
	BackoffPolicy backoffPolicy(TaskForgeProperties properties) {
		TaskForgeProperties.Retry retry = properties.retry();
		return new BackoffPolicy(retry.baseDelay(), retry.maxDelay(), retry.jitterRatio());
	}

}
