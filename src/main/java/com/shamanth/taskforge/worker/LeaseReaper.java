package com.shamanth.taskforge.worker;

import java.util.List;

import com.shamanth.taskforge.config.TaskForgeProperties;
import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobRepository;
import com.shamanth.taskforge.job.JobStatus;
import com.shamanth.taskforge.stream.WebSocketPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lease-based crash recovery. A claim leases a job to one worker until {@code locked_until}; a worker that dies,
 * hangs or loses the database never completes it, so the lease runs out. This reaper periodically moves such jobs
 * back to RETRY (or to DEAD when their attempts are used up) so another worker can run them. Combined with fenced
 * completions, this gives at-least-once execution: a job can run again after a crash, so handlers must be idempotent.
 */
@Component
public class LeaseReaper {

	private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

	private final JobRepository repository;

	private final WebSocketPublisher publisher;

	private final TaskForgeProperties properties;

	public LeaseReaper(JobRepository repository, WebSocketPublisher publisher, TaskForgeProperties properties) {
		this.repository = repository;
		this.publisher = publisher;
		this.properties = properties;
	}

	public List<Job> reapExpiredLeases() {
		List<Job> reaped = repository.reapExpiredLeases();
		if (!reaped.isEmpty()) {
			log.info("Reaped {} job(s) with expired leases: {}", reaped.size(),
					reaped.stream().map(Job::id).toList());
			for (Job job : reaped) {
				if (job.status() == JobStatus.DEAD) {
					log.warn("Job {} ({}) is DEAD after {} attempts: {}", job.id(), job.type(), job.attempts(),
							job.lastError());
				}
			}
			publisher.publishStatus(reaped);
		}
		return reaped;
	}

	@Scheduled(fixedDelayString = "${taskforge.reaper.interval:10s}")
	void scheduledReap() {
		if (!properties.reaper().enabled()) {
			return;
		}
		try {
			reapExpiredLeases();
		}
		catch (DataAccessException ex) {
			log.warn("Lease reaper could not run: {}", ex.getMessage());
		}
	}

}
