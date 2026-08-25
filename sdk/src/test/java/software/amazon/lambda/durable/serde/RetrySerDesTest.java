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

class RetrySerDesTest {

    @Test
    void retriesSerializationWithStrategyDelays() {
        var calls = new AtomicInteger();
        var strategyAttempts = new ArrayList<Integer>();
        var delays = new ArrayList<Duration>();
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value) {
                if (calls.incrementAndGet() < 3) {
                    throw new RetryableSerDesException("transient");
                }
                return "serialized";
            }

            @Override
            public String deserialize(String data) {
                return data;
            }
        };
        var retrySerDes = new RetrySerDes(
                delegate,
                (error, attempt) -> {
                    strategyAttempts.add(attempt);
                    return RetryDecision.retry(Duration.ofMillis(attempt));
                },
                delays::add);

        assertEquals("serialized", retrySerDes.serialize("value"));
        assertEquals(3, calls.get());
        assertEquals(List.of(1, 2), strategyAttempts);
        assertEquals(List.of(Duration.ofMillis(1), Duration.ofMillis(2)), delays);
    }

    @Test
    void retriesDeserialization() {
        var calls = new AtomicInteger();
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value) {
                return value;
            }

            @Override
            public String deserialize(String data) {
                if (calls.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("transient");
                }
                return data;
            }
        };
        var retrySerDes =
                new RetrySerDes(delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});

        assertEquals("value", retrySerDes.deserialize("value"));
        assertEquals(2, calls.get());
    }

    @Test
    void retriesPipelineStageDeserialization() {
        var calls = new AtomicInteger();
        class RetryableStage implements SerDesStage {
            @Override
            public String serialize(String value) {
                return value;
            }

            @Override
            public String deserialize(String data) {
                return data;
            }

            @Override
            public SerDesStageResult deserializePipelineStage(String data) {
                if (calls.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("transient");
                }
                return SerDesStageResult.decodeWithValueCodec(data);
            }
        }
        var delegate = new RetryableStage();
        var retrySerDes =
                new RetrySerDes(delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});

        var result = retrySerDes.deserializePipelineStage("value");

        assertEquals("value", result.value());
        assertTrue(result.skipRemainingStages());
        assertEquals(2, calls.get());
    }

    @Test
    void preservesDelegateContextRequirement() {
        var contextDependentStage = new SerDesStage() {
            @Override
            public boolean requiresDurableContext() {
                return true;
            }

            @Override
            public String serialize(String value) {
                return value;
            }

            @Override
            public String deserialize(String data) {
                return data;
            }
        };

        assertFalse(new RetrySerDes(identityStage(), RetryStrategies.Presets.NO_RETRY).requiresDurableContext());
        assertTrue(new RetrySerDes(contextDependentStage, RetryStrategies.Presets.NO_RETRY).requiresDurableContext());
    }

    @Test
    void doesNotRetryPermanentSerDesFailure() {
        var calls = new AtomicInteger();
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value) {
                calls.incrementAndGet();
                throw new SerDesException("permanent");
            }

            @Override
            public String deserialize(String data) {
                return data;
            }
        };
        var retrySerDes = new RetrySerDes(delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {
            throw new AssertionError("permanent failures must not sleep");
        });

        var failure = assertThrows(SerDesException.class, () -> retrySerDes.serialize("value"));
        assertEquals("permanent", failure.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsInvalidConfigurationAndRetryDelay() {
        var delegate = identityStage();

        assertThrows(NullPointerException.class, () -> new RetrySerDes(null, RetryStrategies.Presets.NO_RETRY));
        assertThrows(NullPointerException.class, () -> new RetrySerDes(delegate, null));
        assertFalse(SerDes.class.isAssignableFrom(RetrySerDes.class));

        var retrySerDes = new RetrySerDes(
                new SerDesStage() {
                    @Override
                    public String serialize(String value) {
                        throw new RetryableSerDesException("transient");
                    }

                    @Override
                    public String deserialize(String data) {
                        return data;
                    }
                },
                (error, attempt) -> RetryDecision.retry(Duration.ofSeconds(-1)),
                delay -> {});

        var failure = assertThrows(SerDesException.class, () -> retrySerDes.serialize("value"));
        assertTrue(failure.getMessage().contains("invalid delay"));
    }

    @Test
    void rethrowsLastRetryableFailureWhenRetriesAreExhausted() {
        var calls = new AtomicInteger();
        var lastFailure = new AtomicReference<RetryableSerDesException>();
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value) {
                var failure = new RetryableSerDesException("attempt-" + calls.incrementAndGet());
                lastFailure.set(failure);
                throw failure;
            }

            @Override
            public String deserialize(String data) {
                return data;
            }
        };
        var retrySerDes = new RetrySerDes(delegate, RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)), delay -> {});

        var thrown = assertThrows(RetryableSerDesException.class, () -> retrySerDes.serialize("value"));
        assertSame(lastFailure.get(), thrown);
        assertEquals("attempt-2", thrown.getMessage());
        assertEquals(2, calls.get());
    }

    @Test
    void restoresInterruptStatusWhenBackoffIsInterrupted() {
        var retryable = new RetryableSerDesException("transient");
        var delegate = new SerDesStage() {
            @Override
            public String serialize(String value) {
                throw retryable;
            }

            @Override
            public String deserialize(String data) {
                return data;
            }
        };
        var retrySerDes =
                new RetrySerDes(delegate, (error, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)), delay -> {
                    throw new InterruptedException("stop");
                });

        try {
            var thrown = assertThrows(SerDesException.class, () -> retrySerDes.serialize("value"));
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
            public String serialize(String value) {
                return value;
            }

            @Override
            public String deserialize(String data) {
                return data;
            }
        };
    }
}
