package com.example.transactions.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryBackoff {
    private final long initialDelayMs;
    private final long maxDelayMs;

    public RetryBackoff(
            @Value("${app.worker.retry.initial-delay-ms:2000}") long initialDelayMs,
            @Value("${app.worker.retry.max-delay-ms:60000}") long maxDelayMs) {
        if (initialDelayMs < 0 || maxDelayMs < initialDelayMs
                || (initialDelayMs == 0 && maxDelayMs != 0)) {
            throw new IllegalArgumentException(
                    "Retry backoff requires 0 <= initial delay <= maximum delay.");
        }
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    public Duration delayAfterAttempt(int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("Retry attempt must be at least 1.");
        }
        long cappedDelay = initialDelayMs;
        for (int i = 1; i < attempt && cappedDelay < maxDelayMs; i++) {
            cappedDelay = cappedDelay > maxDelayMs / 2 ? maxDelayMs : cappedDelay * 2;
        }
        if (cappedDelay == 0) {
            return Duration.ZERO;
        }
        long minimumDelay = Math.max(1, cappedDelay / 2);
        long jitterRange = cappedDelay - minimumDelay + 1;
        return Duration.ofMillis(minimumDelay + ThreadLocalRandom.current().nextLong(jitterRange));
    }
}
