package com.shamanth.taskforge.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer, DisposableBean {

	private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

	public SchedulingConfig() {
		scheduler.setPoolSize(2);
		scheduler.setThreadNamePrefix("taskforge-scheduler-");
		scheduler.initialize();
	}

	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		// Dedicated scheduler: the WebSocket broker's TaskScheduler bean makes Boot's default scheduler back off.
		registrar.setTaskScheduler(scheduler);
	}

	@Override
	public void destroy() {
		scheduler.shutdown();
	}

}
