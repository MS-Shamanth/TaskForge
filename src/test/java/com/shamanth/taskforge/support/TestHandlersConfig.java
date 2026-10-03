package com.shamanth.taskforge.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestHandlersConfig {

	@Bean
	FlakyJobHandler flakyJobHandler() {
		return new FlakyJobHandler();
	}

	@Bean
	AlwaysFailingJobHandler alwaysFailingJobHandler() {
		return new AlwaysFailingJobHandler();
	}

	@Bean
	CountingJobHandler countingJobHandler() {
		return new CountingJobHandler();
	}

}
