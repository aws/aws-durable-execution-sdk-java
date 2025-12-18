package com.amazonaws.lambda.durable.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RetryDecisionTest {

    @Test
    void testRetryDecision() {
        var delay = Duration.ofSeconds(5);
        var decision = RetryDecision.retry(delay);

        assertTrue(decision.shouldRetry());
        assertEquals(delay, decision.delay());
    }

    @Test
    void testFailDecision() {
        var decision = RetryDecision.fail();

        assertFalse(decision.shouldRetry());
        assertEquals(Duration.ZERO, decision.delay());
    }

    @Test
    void testRetryWithNullDelay() {
        var decision = RetryDecision.retry(null);

        assertTrue(decision.shouldRetry());
        assertEquals(Duration.ZERO, decision.delay());
    }

    @Test
    void testToString() {
        var retry = RetryDecision.retry(Duration.ofSeconds(10));
        var fail = RetryDecision.fail();

        assertTrue(retry.toString().contains("retry after"));
        assertTrue(retry.toString().contains("10"));
        assertTrue(fail.toString().contains("fail"));
    }
}
