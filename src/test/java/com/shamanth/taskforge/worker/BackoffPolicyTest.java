package com.shamanth.taskforge.worker;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BackoffPolicyTest {

	private static final Duration BASE = Duration.ofSeconds(1);

	private static final Duration CAP = Duration.ofMinutes(5);

	private final BackoffPolicy noJitter = new BackoffPolicy(BASE, CAP, 0.0);

	@Test
	void delayDoublesWithEachAttempt() {
		assertThat(noJitter.delayFor(1)).isEqualTo(Duration.ofSeconds(1));
		assertThat(noJitter.delayFor(2)).isEqualTo(Duration.ofSeconds(2));
		assertThat(noJitter.delayFor(3)).isEqualTo(Duration.ofSeconds(4));
		assertThat(noJitter.delayFor(4)).isEqualTo(Duration.ofSeconds(8));
		assertThat(noJitter.delayFor(5)).isEqualTo(Duration.ofSeconds(16));
		assertThat(noJitter.delayFor(9)).isEqualTo(Duration.ofSeconds(256));
	}

	@Test
	void delayIsCappedWithoutOverflow() {
		for (int attempt : new int[] { 10, 20, 63, 64, 10_000 }) {
			assertThat(noJitter.delayFor(attempt)).as("attempt %d", attempt).isEqualTo(CAP);
		}
	}

	@Test
	void jitterAddsUpToTheConfiguredRatioAndNeverPassesTheCap() {
		BackoffPolicy jittered = new BackoffPolicy(BASE, CAP, 0.2, () -> 0.999);

		assertThat(jittered.delayFor(1)).isBetween(Duration.ofSeconds(1), Duration.ofMillis(1200));
		for (int attempt = 1; attempt <= 40; attempt++) {
			assertThat(jittered.delayFor(attempt)).as("attempt %d", attempt)
				.isGreaterThanOrEqualTo(noJitter.delayFor(attempt))
				.isLessThanOrEqualTo(CAP);
		}
	}

	@Test
	void delayNeverDecreases() {
		Duration previous = Duration.ZERO;
		for (int attempt = 1; attempt <= 40; attempt++) {
			Duration delay = noJitter.delayFor(attempt);
			assertThat(delay).as("attempt %d", attempt).isGreaterThanOrEqualTo(previous);
			previous = delay;
		}
	}

	@Test
	void rejectsInvalidArguments() {
		assertThatIllegalArgumentException().isThrownBy(() -> noJitter.delayFor(0));
		assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(Duration.ZERO, CAP, 0.2));
		assertThatIllegalArgumentException().isThrownBy(() -> new BackoffPolicy(BASE, CAP, 1.5));
	}

}
