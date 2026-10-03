package com.shamanth.taskforge.worker;

import com.shamanth.taskforge.config.TaskForgeProperties;
import com.shamanth.taskforge.job.JobRepository;
import com.shamanth.taskforge.stream.WebSocketPublisher;

import org.springframework.stereotype.Component;

@Component
public class WorkerFactory {

	private final JobRepository repository;

	private final JobExecutor jobExecutor;

	private final WebSocketPublisher publisher;

	private final TaskForgeProperties properties;

	public WorkerFactory(JobRepository repository, JobExecutor jobExecutor, WebSocketPublisher publisher,
			TaskForgeProperties properties) {
		this.repository = repository;
		this.jobExecutor = jobExecutor;
		this.publisher = publisher;
		this.properties = properties;
	}

	public Worker create(String id, int threads) {
		return new Worker(id, threads, properties.worker().lease(), repository, jobExecutor, publisher);
	}

}
