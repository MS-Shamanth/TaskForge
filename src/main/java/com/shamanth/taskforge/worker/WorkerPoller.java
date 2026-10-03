package com.shamanth.taskforge.worker;

import java.util.UUID;

import com.shamanth.taskforge.config.TaskForgeProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty(name = "taskforge.worker.enabled", matchIfMissing = true)
public class WorkerPoller {

	private static final Logger log = LoggerFactory.getLogger(WorkerPoller.class);

	private final Worker worker;

	public WorkerPoller(WorkerFactory workerFactory, TaskForgeProperties properties) {
		String id = "worker-" + UUID.randomUUID().toString().substring(0, 8);
		this.worker = workerFactory.create(id, properties.worker().threads());
		log.info("Started {} with {} threads", worker.id(), worker.threads());
	}

	@Scheduled(fixedDelayString = "${taskforge.worker.poll-interval:500ms}")
	void poll() {
		try {
			worker.pollOnce();
		}
		catch (DataAccessException ex) {
			log.warn("{} could not poll for jobs: {}", worker.id(), ex.getMessage());
		}
	}

	@PreDestroy
	void stop() {
		worker.shutdown();
	}

}
