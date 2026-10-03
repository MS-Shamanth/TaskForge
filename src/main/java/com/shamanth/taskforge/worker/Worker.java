package com.shamanth.taskforge.worker;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.shamanth.taskforge.job.Job;
import com.shamanth.taskforge.job.JobRepository;
import com.shamanth.taskforge.stream.WebSocketPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

public class Worker {

	private static final Logger log = LoggerFactory.getLogger(Worker.class);

	private final String id;

	private final int threads;

	private final Duration lease;

	private final JobRepository repository;

	private final JobExecutor jobExecutor;

	private final WebSocketPublisher publisher;

	private final ExecutorService executor;

	private final AtomicInteger inFlight = new AtomicInteger();

	private volatile boolean stopped;

	public Worker(String id, int threads, Duration lease, JobRepository repository, JobExecutor jobExecutor,
			WebSocketPublisher publisher) {
		if (threads < 1) {
			throw new IllegalArgumentException("threads must be >= 1, was " + threads);
		}
		this.id = id;
		this.threads = threads;
		this.lease = lease;
		this.repository = repository;
		this.jobExecutor = jobExecutor;
		this.publisher = publisher;
		this.executor = Executors.newFixedThreadPool(threads, new CustomizableThreadFactory(id + "-t"));
	}

	public synchronized int pollOnce() {
		int free = threads - inFlight.get();
		if (stopped || free <= 0) {
			return 0;
		}
		List<Job> claimed = repository.claim(id, free, lease);
		for (Job job : claimed) {
			publisher.publishStatus(job);
			inFlight.incrementAndGet();
			try {
				executor.execute(() -> run(job));
			}
			catch (RejectedExecutionException ex) {
				inFlight.decrementAndGet();
				log.warn("Worker {} could not start job {}; the lease reaper will requeue it", id, job.id());
			}
		}
		return claimed.size();
	}

	private void run(Job job) {
		try {
			jobExecutor.execute(job, id);
		}
		catch (Throwable ex) {
			log.error("Worker {} failed to record the result of job {}; its lease will expire", id, job.id(), ex);
		}
		finally {
			inFlight.decrementAndGet();
		}
	}

	public void shutdown() {
		synchronized (this) {
			stopped = true;
			executor.shutdown();
		}
		try {
			if (!executor.awaitTermination(lease.toMillis(), TimeUnit.MILLISECONDS)) {
				executor.shutdownNow();
			}
		}
		catch (InterruptedException ex) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
		log.info("Worker {} stopped", id);
	}

	public String id() {
		return id;
	}

	public int threads() {
		return threads;
	}

}
