package com.shamanth.taskforge.worker;

import java.util.Optional;

import com.shamanth.taskforge.handler.JobHandler;
import com.shamanth.taskforge.handler.JobHandlerRegistry;
import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobRepository;
import com.shamanth.taskforge.stream.WebSocketPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

@Component
public class JobExecutor {

	private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

	private static final int MAX_ERROR_LENGTH = 2000;

	private final JobHandlerRegistry handlers;

	private final JobRepository repository;

	private final BackoffPolicy backoff;

	private final WebSocketPublisher publisher;

	public JobExecutor(JobHandlerRegistry handlers, JobRepository repository, BackoffPolicy backoff,
			WebSocketPublisher publisher) {
		this.handlers = handlers;
		this.repository = repository;
		this.backoff = backoff;
		this.publisher = publisher;
	}

	/** Runs a claimed job and records the outcome with a fenced update (rejected if the lease was lost). */
	public void execute(Job job, String workerId) {
		Exception failure = null;
		try {
			JobHandler handler = handlers.find(job.type())
				.orElseThrow(() -> new IllegalStateException("No handler registered for type " + job.type()));
			handler.handle(job);
		}
		catch (Exception ex) {
			failure = ex;
		}

		Optional<Job> result = (failure == null) ? repository.markSucceeded(job.id(), workerId, job.attempts())
				: recordFailure(job, workerId, failure);
		if (result.isPresent()) {
			log.debug("Job {} attempt {} on {} -> {}", job.id(), job.attempts(), workerId, result.get().status());
			publisher.publishStatus(result.get());
		}
		else {
			log.warn("Result for job {} attempt {} from {} rejected: lease lost", job.id(), job.attempts(), workerId);
		}
		if (failure instanceof InterruptedException) {
			Thread.currentThread().interrupt();
		}
	}

	private Optional<Job> recordFailure(Job job, String workerId, Exception failure) {
		String error = describe(failure);
		if (job.attempts() < job.maxAttempts()) {
			return repository.markRetry(job.id(), workerId, job.attempts(), backoff.delayFor(job.attempts()), error);
		}
		Optional<Job> dead = repository.markDead(job.id(), workerId, job.attempts(), error);
		if (dead.isPresent()) {
			log.warn("Job {} ({}) is DEAD after {} attempts: {}", job.id(), job.type(), job.attempts(), error);
		}
		return dead;
	}

	private static String describe(Exception failure) {
		String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
		return (error.length() > MAX_ERROR_LENGTH) ? error.substring(0, MAX_ERROR_LENGTH) : error;
	}

}
