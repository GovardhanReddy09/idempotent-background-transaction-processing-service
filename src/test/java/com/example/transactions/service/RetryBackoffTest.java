package com.example.transactions.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryBackoffTest {
    @Test
    void delayGrowsExponentiallyWithJitterAndHonorsMaximum() {
        RetryBackoff backoff = new RetryBackoff(1_000, 4_000);

        assertDelayInRange(backoff.delayAfterAttempt(1), 500, 1_000);
        assertDelayInRange(backoff.delayAfterAttempt(2), 1_000, 2_000);
        assertDelayInRange(backoff.delayAfterAttempt(3), 2_000, 4_000);
        assertDelayInRange(backoff.delayAfterAttempt(8), 2_000, 4_000);
    }

    @Test
    void zeroDelayCanDisableBackoffForDeterministicTests() {
        assertEquals(Duration.ZERO, new RetryBackoff(0, 0).delayAfterAttempt(1));
    }

    @Test
    void rejectsInvalidAttemptAndDelayConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new RetryBackoff(1, 2).delayAfterAttempt(0));
    }

    private void assertDelayInRange(Duration duration, long minimumMs, long maximumMs) {
        long delayMs = duration.toMillis();
        assertTrue(delayMs >= minimumMs && delayMs <= maximumMs,
                () -> "Expected delay between " + minimumMs + " and " + maximumMs + " ms, got " + delayMs);
    }
}
