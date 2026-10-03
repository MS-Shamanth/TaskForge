package com.shamanth.taskforge.worker;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Exponential backoff with jitter for failed jobs.
 * <p>
 * {@code delay(attempt) = min(maxDelay, base * 2^(attempt - 1) + jitter)}, where {@code attempt} is the number of
 * attempts already made and {@code jitter} is a random share (up to {@code jitterRatio}) of the exponential part.
 * Jitter de-synchronises jobs that failed together (for example during an outage of the same webhook target), so
 * their retries do not hit the dependency again as one storm. The cap is applied last, so no delay ever exceeds
 * {@code maxDelay}, jitter included. The exponential part is computed overflow-safe: once {@code base << (attempt - 1)}
 * would pass the cap (or the shift itself would overflow a {@code long}), the cap is used directly.
 */
public final class BackoffPolicy {

	private final long baseNanos;

	private final long maxNanos;

	private final double jitterRatio;

	private final DoubleSupplier random;

	public BackoffPolicy(Duration baseDelay, Duration maxDelay, double jitterRatio) {
		this(baseDelay, maxDelay, jitterRatio, () -> ThreadLocalRandom.current().nextDouble());
	}

	public BackoffPolicy(Duration baseDelay, Duration maxDelay, double jitterRatio, DoubleSupplier random) {
		if (baseDelay == null || baseDelay.isZero() || baseDelay.isNegative()) {
			throw new IllegalArgumentException("baseDelay must be positive, was " + baseDelay);
		}
		if (maxDelay == null || maxDelay.compareTo(baseDelay) < 0) {
			throw new IllegalArgumentException("maxDelay must be >= baseDelay, was " + maxDelay);
		}
		if (!(jitterRatio >= 0.0 && jitterRatio <= 1.0)) {
			throw new IllegalArgumentException("jitterRatio must be between 0 and 1, was " + jitterRatio);
		}
		if (random == null) {
			throw new IllegalArgumentException("random must not be null");
		}
		this.baseNanos = baseDelay.toNanos();
		this.maxNanos = maxDelay.toNanos();
		this.jitterRatio = jitterRatio;
		this.random = random;
	}

	/**
	 * Delay before the next attempt.
	 * @param attempt attempts already made (1 after the first failure)
	 */
	public Duration delayFor(int attempt) {
		if (attempt < 1) {
			throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
		}
		int shift = attempt - 1;
		long exponential = (shift >= 62 || baseNanos > (maxNanos >> shift)) ? maxNanos : baseNanos << shift;
		long jitter = (long) (exponential * jitterRatio * random.getAsDouble());
		// exponential <= maxNanos here, so comparing against the remaining headroom cannot overflow.
		return Duration.ofNanos(exponential + Math.min(jitter, maxNanos - exponential));
	}

}
