package com.shamanth.taskforge.job;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.shamanth.taskforge.config.TaskForgeProperties;
import com.shamanth.taskforge.handler.JobHandler;
import com.shamanth.taskforge.handler.JobHandlerRegistry;
import com.shamanth.taskforge.stream.WebSocketPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Service;

@Service
public class JobService {

	private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

	private static final int MAX_PAGE_SIZE = 100;

	private final JobRepository repository;

	private final JobHandlerRegistry handlers;

	private final WebSocketPublisher publisher;

	private final TaskForgeProperties properties;

	private final JsonMapper jsonMapper;

	public JobService(JobRepository repository, JobHandlerRegistry handlers, WebSocketPublisher publisher,
			TaskForgeProperties properties, JsonMapper jsonMapper) {
		this.repository = repository;
		this.handlers = handlers;
		this.publisher = publisher;
		this.properties = properties;
		this.jsonMapper = jsonMapper;
	}

	public SubmitResult submit(NewJob request) {
		String type = (request.type() != null) ? request.type().trim().toUpperCase(Locale.ROOT) : "";
		JobHandler handler = handlers.find(type)
			.orElseThrow(() -> new InvalidJobRequestException(
					"Unknown job type '" + request.type() + "'. Supported: " + handlers.types()));
		JsonNode payload = request.payload();
		if (payload == null || !payload.isObject()) {
			throw new InvalidJobRequestException("payload must be a JSON object");
		}
		try {
			handler.validate(payload);
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidJobRequestException(ex.getMessage());
		}
		String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
		int priority = (request.priority() != null) ? request.priority() : 0;
		int maxAttempts = (request.maxAttempts() != null) ? request.maxAttempts()
				: properties.retry().defaultMaxAttempts();

		Optional<Job> inserted = repository.insert(type, jsonMapper.writeValueAsString(payload), priority,
				request.runAt(), maxAttempts, idempotencyKey);
		if (inserted.isPresent()) {
			publisher.publishStatus(inserted.get());
			return new SubmitResult(inserted.get(), true);
		}
		Job existing = repository.findByIdempotencyKey(idempotencyKey)
			.orElseThrow(() -> new IllegalStateException("Idempotency key conflict but no job found for it"));
		return new SubmitResult(existing, false);
	}

	public Job get(long id) {
		return repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
	}

	public JobPage list(JobStatus status, int page, int size) {
		if (page < 0) {
			throw new InvalidJobRequestException("page must be >= 0");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new InvalidJobRequestException("size must be between 1 and " + MAX_PAGE_SIZE);
		}
		List<Job> items = repository.findPage(status, size, (long) page * size);
		return new JobPage(items, page, size, repository.count(status));
	}

	public Map<JobStatus, Long> stats() {
		return repository.countByStatus();
	}

	public Job retry(long id) {
		Optional<Job> requeued = repository.requeueDead(id);
		if (requeued.isEmpty()) {
			Job job = get(id);
			throw new JobConflictException(
					"Job " + id + " is " + job.status() + "; only DEAD jobs can be retried");
		}
		publisher.publishStatus(requeued.get());
		return requeued.get();
	}

	public Job cancel(long id) {
		Optional<Job> cancelled = repository.cancel(id);
		if (cancelled.isEmpty()) {
			Job job = get(id);
			throw new JobConflictException(
					"Job " + id + " is " + job.status() + "; only QUEUED or RETRY jobs can be cancelled");
		}
		publisher.publishStatus(cancelled.get());
		return cancelled.get();
	}

	private static String normalizeIdempotencyKey(String key) {
		if (key == null) {
			return null;
		}
		String trimmed = key.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
			throw new InvalidJobRequestException(
					"Idempotency-Key must be 1-" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
		}
		return trimmed;
	}

}
