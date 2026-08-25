// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;

class RetrySerDesStageTest {
    private static final Object ORIGINAL_VALUE = new Object();
    private static final SerDesContext CONTEXT = SerDesContext.forExecution(
                    "arn", "invocation", "execution", SerDesPayloadKind.RESULT)
            .withOriginalValue(ORIGINAL_VALUE);

    @Test
    void retriesSerializationWithStrategyDelays() {
        var calls = new AtomicInteger();
        var strategyAttempts = new ArrayList<Integer>();
        var delays = new ArrayList<Duration>();
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                assertSame(CONTEXT, context);
                assertSame(ORIGINAL_VALUE, context.originalValue());
                if (calls.incrementAndGet() < 3) {
                    throw new RetryableSerDesException("transient");
                }
                return "serialized";
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                return data;
            }
        };
        var retrySerDes = new RetrySerDesStage(
                delegate,
                (error, attempt) -> {
                    strategyAttempts.add(attempt);
                    return RetryDecision.retry(Duration.ofMillis(attempt));
                },
                delays::add);

        assertEquals("serialized", retrySerDes.serialize("value", CONTEXT));
        assertEquals(3, calls.get());
        assertEquals(List.of(1, 2), strategyAttempts);
        assertEquals(List.of(Duration.ofMillis(1), Duration.ofMillis(2)), delays);
    }

    @Test
    void retriesDeserialization() {
        var calls = new AtomicInteger();
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                return value;
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                assertSame(CONTEXT, context);
                if (calls.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("transient");
                }
                return data;
            }
        };
        var retrySerDes =
                new RetrySerDesStage(delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});

        assertEquals("value", retrySerDes.deserialize("value", CONTEXT));
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotRetryPermanentSerDesFailure() {
        var calls = new AtomicInteger();
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                calls.incrementAndGet();
                throw new SerDesException("permanent");
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                return data;
            }
        };
        var retrySerDes =
                new RetrySerDesStage(delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {
                    throw new AssertionError("permanent failures must not sleep");
                });

        var failure = assertThrows(SerDesException.class, () -> retrySerDes.serialize("value", CONTEXT));
        assertEquals("permanent", failure.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsInvalidConfigurationAndRetryDelay() {
        var delegate = identityStage();

        assertThrows(NullPointerException.class, () -> new RetrySerDesStage(null, RetryStrategies.Presets.NO_RETRY));
        assertThrows(NullPointerException.class, () -> new RetrySerDesStage(delegate, null));
        assertFalse(SerDes.class.isAssignableFrom(RetrySerDesStage.class));

        var retrySerDes = new RetrySerDesStage(
                new SerDesStage() {
                    @Override
                    public String serialize(String value, SerDesContext context) {
                        throw new RetryableSerDesException("transient");
                    }

                    @Override
                    public String deserialize(String data, SerDesContext context) {
                        return data;
                    }
                },
                (error, attempt) -> RetryDecision.retry(Duration.ofSeconds(-1)),
                delay -> {});

        var failure = assertThrows(SerDesException.class, () -> retrySerDes.serialize("value", CONTEXT));
        assertTrue(failure.getMessage().contains("invalid delay"));
    }

    @Test
    void rethrowsLastRetryableFailureWhenRetriesAreExhausted() {
        var calls = new AtomicInteger();
        var lastFailure = new AtomicReference<RetryableSerDesException>();
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                var failure = new RetryableSerDesException("attempt-" + calls.incrementAndGet());
                lastFailure.set(failure);
                throw failure;
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                return data;
            }
        };
        var retrySerDes =
                new RetrySerDesStage(delegate, RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)), delay -> {});

        var thrown = assertThrows(RetryableSerDesException.class, () -> retrySerDes.serialize("value", CONTEXT));
        assertSame(lastFailure.get(), thrown);
        assertEquals("attempt-2", thrown.getMessage());
        assertEquals(2, calls.get());
    }

    @Test
    void restoresInterruptStatusWhenBackoffIsInterrupted() {
        var retryable = new RetryableSerDesException("transient");
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                throw retryable;
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                return data;
            }
        };
        var retrySerDes = new RetrySerDesStage(
                delegate, (error, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)), delay -> {
                    throw new InterruptedException("stop");
                });

        try {
            var thrown = assertThrows(SerDesException.class, () -> retrySerDes.serialize("value", CONTEXT));
            assertTrue(thrown.getMessage().contains("Interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertSame(retryable, thrown.getSuppressed()[0]);
        } finally {
            Thread.interrupted();
        }
    }

    private static SerDesStage identityStage() {
        return new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                return value;
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                return data;
            }
        };
    }
}
