package com.shamanth.taskforge.job;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class JobRepository {

	private static final String INSERT_SQL = """
			INSERT INTO jobs (type, payload, priority, run_at, max_attempts, idempotency_key)
			VALUES (?, ?::jsonb, ?, COALESCE(?::timestamptz, now()), ?, ?)
			ON CONFLICT (idempotency_key) DO NOTHING
			RETURNING *
			""";

	private static final String FIND_BY_ID_SQL = "SELECT * FROM jobs WHERE id = ?";

	private static final String FIND_BY_IDEMPOTENCY_KEY_SQL = "SELECT * FROM jobs WHERE idempotency_key = ?";

	private static final String PAGE_SQL = "SELECT * FROM jobs ORDER BY id DESC LIMIT ? OFFSET ?";

	private static final String PAGE_BY_STATUS_SQL = "SELECT * FROM jobs WHERE status = ? ORDER BY id DESC LIMIT ? OFFSET ?";

	private static final String COUNT_SQL = "SELECT count(*) FROM jobs";

	private static final String COUNT_BY_STATUS_SQL = "SELECT count(*) FROM jobs WHERE status = ?";

	private static final String COUNT_GROUPED_SQL = "SELECT status, count(*) AS n FROM jobs GROUP BY status";

	/*
	 * Claim query, run as one autocommit statement. The inner SELECT picks due jobs (QUEUED or RETRY with
	 * run_at <= now()), highest priority first and the oldest run_at within a priority, and row-locks them with
	 * FOR UPDATE SKIP LOCKED: rows another transaction has already locked (a concurrent claim) are skipped instead
	 * of waited on, so concurrent workers never block each other and never get the same row. A row a concurrent
	 * claim has already committed as RUNNING is re-checked against the WHERE clause and dropped. The outer UPDATE
	 * then takes the locked rows: RUNNING, owned by this worker, leased until now() + lease on the database clock,
	 * and attempts + 1 (counted at claim time, so a crash still uses up an attempt).
	 */
	private static final String CLAIM_SQL = """
			UPDATE jobs
			SET status = 'RUNNING', locked_by = ?, locked_until = now() + (? * interval '1 second'),
			    attempts = attempts + 1, updated_at = now()
			WHERE id IN (
			    SELECT id FROM jobs
			    WHERE status IN ('QUEUED','RETRY') AND run_at <= now()
			    ORDER BY priority DESC, run_at
			    LIMIT ?
			    FOR UPDATE SKIP LOCKED)
			RETURNING *
			""";

	/*
	 * Fenced completion and failure updates. A result is only accepted from the claim that still owns the job:
	 * the same worker (locked_by), the same claim (attempts, which every claim increments) and still RUNNING.
	 * After the lease expired and the reaper requeued the job, or the job was re-claimed, the stale result matches
	 * no row and is rejected (empty result). locked_by alone is not enough, because a worker can re-claim its own
	 * reaped job; the attempt number tells those two claims apart. Every transition out of RUNNING clears the lock.
	 */
	private static final String MARK_SUCCEEDED_SQL = """
			UPDATE jobs
			SET status = 'SUCCEEDED', locked_by = NULL, locked_until = NULL, updated_at = now()
			WHERE id = ? AND locked_by = ? AND attempts = ? AND status = 'RUNNING'
			RETURNING *
			""";

	private static final String MARK_RETRY_SQL = """
			UPDATE jobs
			SET status = 'RETRY', run_at = now() + (? * interval '1 second'), last_error = ?,
			    locked_by = NULL, locked_until = NULL, updated_at = now()
			WHERE id = ? AND locked_by = ? AND attempts = ? AND status = 'RUNNING'
			RETURNING *
			""";

	private static final String MARK_DEAD_SQL = """
			UPDATE jobs
			SET status = 'DEAD', last_error = ?, locked_by = NULL, locked_until = NULL, updated_at = now()
			WHERE id = ? AND locked_by = ? AND attempts = ? AND status = 'RUNNING'
			RETURNING *
			""";

	/*
	 * Lease reaper (crash recovery). A RUNNING job whose lease ran out belongs to a worker that crashed, hung or
	 * lost its database connection. It goes back to RETRY, runnable at once, or to DEAD when it has no attempts
	 * left: the claim already counted the attempt, so a job that keeps killing its worker still ends up DEAD.
	 * SET expressions read the old row, so last_error names the worker that held the lease. A late fenced update
	 * from that worker then finds the row no longer RUNNING (or blocks on the row lock and re-checks) and is
	 * rejected; a fenced update that commits first leaves nothing for this statement to match.
	 */
	private static final String REAP_SQL = """
			UPDATE jobs
			SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'RETRY' END,
			    last_error = 'Lease expired while held by ' || COALESCE(locked_by, 'unknown'),
			    locked_by = NULL, locked_until = NULL, updated_at = now()
			WHERE status = 'RUNNING' AND locked_until < now()
			RETURNING *
			""";

	private static final String REQUEUE_DEAD_SQL = """
			UPDATE jobs
			SET status = 'QUEUED', attempts = 0, run_at = now(), locked_by = NULL, locked_until = NULL,
			    updated_at = now()
			WHERE id = ? AND status = 'DEAD'
			RETURNING *
			""";

	private static final String CANCEL_SQL = """
			UPDATE jobs
			SET status = 'CANCELLED', locked_by = NULL, locked_until = NULL, updated_at = now()
			WHERE id = ? AND status IN ('QUEUED','RETRY')
			RETURNING *
			""";

	private static final Comparator<Job> CLAIM_ORDER = Comparator.comparingInt(Job::priority)
		.reversed()
		.thenComparing(Job::runAt)
		.thenComparingLong(Job::id);

	private final JdbcTemplate jdbc;

	private final JobRowMapper rowMapper;

	public JobRepository(JdbcTemplate jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.rowMapper = new JobRowMapper(jsonMapper);
	}

	public Optional<Job> insert(String type, String payloadJson, int priority, Instant runAt, int maxAttempts,
			String idempotencyKey) {
		return single(INSERT_SQL, type, payloadJson, priority, timestamp(runAt), maxAttempts, idempotencyKey);
	}

	public Optional<Job> findById(long id) {
		return single(FIND_BY_ID_SQL, id);
	}

	public Optional<Job> findByIdempotencyKey(String idempotencyKey) {
		return single(FIND_BY_IDEMPOTENCY_KEY_SQL, idempotencyKey);
	}

	public List<Job> findPage(JobStatus status, int limit, long offset) {
		if (status == null) {
			return jdbc.query(PAGE_SQL, rowMapper, limit, offset);
		}
		return jdbc.query(PAGE_BY_STATUS_SQL, rowMapper, status.name(), limit, offset);
	}

	public long count(JobStatus status) {
		Long count = (status == null) ? jdbc.queryForObject(COUNT_SQL, Long.class)
				: jdbc.queryForObject(COUNT_BY_STATUS_SQL, Long.class, status.name());
		return (count != null) ? count : 0L;
	}

	public Map<JobStatus, Long> countByStatus() {
		Map<JobStatus, Long> counts = new EnumMap<>(JobStatus.class);
		for (JobStatus status : JobStatus.values()) {
			counts.put(status, 0L);
		}
		RowCallbackHandler collect = (rs) -> counts.put(JobStatus.valueOf(rs.getString("status")), rs.getLong("n"));
		jdbc.query(COUNT_GROUPED_SQL, collect);
		return counts;
	}

	public List<Job> claim(String workerId, int limit, Duration lease) {
		List<Job> claimed = new ArrayList<>(jdbc.query(CLAIM_SQL, rowMapper, workerId, seconds(lease), limit));
		claimed.sort(CLAIM_ORDER);
		return claimed;
	}

	public Optional<Job> markSucceeded(long id, String workerId, int attempt) {
		return single(MARK_SUCCEEDED_SQL, id, workerId, attempt);
	}

	public Optional<Job> markRetry(long id, String workerId, int attempt, Duration delay, String error) {
		return single(MARK_RETRY_SQL, seconds(delay), error, id, workerId, attempt);
	}

	public Optional<Job> markDead(long id, String workerId, int attempt, String error) {
		return single(MARK_DEAD_SQL, error, id, workerId, attempt);
	}

	public List<Job> reapExpiredLeases() {
		return jdbc.query(REAP_SQL, rowMapper);
	}

	public Optional<Job> requeueDead(long id) {
		return single(REQUEUE_DEAD_SQL, id);
	}

	public Optional<Job> cancel(long id) {
		return single(CANCEL_SQL, id);
	}

	private Optional<Job> single(String sql, Object... args) {
		List<Job> rows = jdbc.query(sql, rowMapper, args);
		return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return (instant != null) ? instant.atOffset(ZoneOffset.UTC) : null;
	}

	private static double seconds(Duration duration) {
		return duration.toMillis() / 1000.0;
	}

}
