package com.shamanth.taskforge.support;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.shamanth.taskforge.handler.JobHandler;
import com.shamanth.taskforge.job.Job;
import tools.jackson.databind.JsonNode;

/**
 * Records every execution: per job id, per claiming worker ({@code job.lockedBy()}) and per thread. An optional
 * {@code sleepMs} in the payload simulates work.
 */
public class CountingJobHandler implements JobHandler {

	private final ConcurrentHashMap<Long, AtomicInteger> executionsByJob = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<String, AtomicInteger> executionsByWorker = new ConcurrentHashMap<>();

	private final Set<String> threadNames = ConcurrentHashMap.newKeySet();

	@Override
	public String type() {
		return "COUNTING";
	}

	@Override
	public void handle(Job job) throws InterruptedException {
		executionsByJob.computeIfAbsent(job.id(), (id) -> new AtomicInteger()).incrementAndGet();
		executionsByWorker.computeIfAbsent(String.valueOf(job.lockedBy()), (w) -> new AtomicInteger()).incrementAndGet();
		threadNames.add(Thread.currentThread().getName());
		JsonNode sleepMs = job.payload().get("sleepMs");
		if (sleepMs != null && sleepMs.isNumber()) {
			Thread.sleep(sleepMs.asLong());
		}
	}

	public void reset() {
		executionsByJob.clear();
		executionsByWorker.clear();
		threadNames.clear();
	}

	public int executionsOf(long jobId) {
		AtomicInteger count = executionsByJob.get(jobId);
		return (count != null) ? count.get() : 0;
	}

	public Map<Long, Integer> executionsByJob() {
		Map<Long, Integer> snapshot = new TreeMap<>();
		executionsByJob.forEach((id, count) -> snapshot.put(id, count.get()));
		return snapshot;
	}

	public Map<String, Integer> executionsByWorker() {
		Map<String, Integer> snapshot = new TreeMap<>();
		executionsByWorker.forEach((worker, count) -> snapshot.put(worker, count.get()));
		return snapshot;
	}

	public Set<String> threadNames() {
		return Set.copyOf(threadNames);
	}

}
