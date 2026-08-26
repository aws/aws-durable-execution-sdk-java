// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;

class RetryBinarySerDesStageTest {
    private static final Object ORIGINAL_VALUE = new Object();
    private static final SerDesContext CONTEXT = SerDesContext.forExecution(
                    "arn", "invocation", "execution", SerDesPayloadKind.RESULT)
            .withOriginalValue(ORIGINAL_VALUE);

    @Test
    void retriesSerializationAndPreservesContext() {
        var calls = new AtomicInteger();
        var observedContext = new AtomicReference<SerDesContext>();
        var delegate = new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                observedContext.set(context);
                if (calls.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("transient");
                }
                return value;
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                return data;
            }
        };
        var stage = new RetryBinarySerDesStage(
                delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});
        var value = new byte[] {1, 2, 3};

        assertArrayEquals(value, stage.serialize(value, CONTEXT));
        assertSame(CONTEXT, observedContext.get());
        assertSame(ORIGINAL_VALUE, observedContext.get().originalValue());
    }

    @Test
    void retriesDeserialization() {
        var calls = new AtomicInteger();
        var delegate = new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                return value;
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                if (calls.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("transient");
                }
                return data;
            }
        };
        var stage = new RetryBinarySerDesStage(
                delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});
        var value = new byte[] {1, 2, 3};

        assertArrayEquals(value, stage.deserialize(value, CONTEXT));
    }

    @Test
    void retriesSerializationWithFreshInputForEveryAttempt() {
        var calls = new AtomicInteger();
        var delegate = new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                value[0]++;
                if (calls.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("transient");
                }
                return value;
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                return data;
            }
        };
        var stage = new RetryBinarySerDesStage(
                delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});
        var value = new byte[] {1, 2, 3};

        assertArrayEquals(new byte[] {2, 2, 3}, stage.serialize(value, CONTEXT));
        assertArrayEquals(new byte[] {1, 2, 3}, value);
    }

    @Test
    void retriesDeserializationWithFreshInputForEveryAttempt() {
        var calls = new AtomicInteger();
        var delegate = new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                return value;
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                data[0]--;
                if (calls.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("transient");
                }
                return data;
            }
        };
        var stage = new RetryBinarySerDesStage(
                delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});
        var value = new byte[] {3, 2, 1};

        assertArrayEquals(new byte[] {2, 2, 1}, stage.deserialize(value, CONTEXT));
        assertArrayEquals(new byte[] {3, 2, 1}, value);
    }

    @Test
    void doesNotRetryPermanentFailures() {
        var calls = new AtomicInteger();
        var delegate = new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                calls.incrementAndGet();
                throw new SerDesException("permanent");
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                return data;
            }
        };
        var stage =
                new RetryBinarySerDesStage(delegate, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {
                    throw new AssertionError("permanent failures must not sleep");
                });

        assertThrows(SerDesException.class, () -> stage.serialize(new byte[0], CONTEXT));
        assertEquals(1, calls.get());
    }

    @Test
    void validatesConfigurationAndRethrowsExhaustedFailure() {
        var delegate = identityStage();
        assertThrows(
                NullPointerException.class, () -> new RetryBinarySerDesStage(null, RetryStrategies.Presets.NO_RETRY));
        assertThrows(NullPointerException.class, () -> new RetryBinarySerDesStage(delegate, null));
        assertFalse(SerDesStage.class.isAssignableFrom(RetryBinarySerDesStage.class));

        var retryable = new RetryableSerDesException("transient");
        var failingDelegate = new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                throw retryable;
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                return data;
            }
        };
        var stage = new RetryBinarySerDesStage(failingDelegate, RetryStrategies.Presets.NO_RETRY, delay -> {});

        assertSame(
                retryable, assertThrows(RetryableSerDesException.class, () -> stage.serialize(new byte[0], CONTEXT)));
    }

    private static BinarySerDesStage identityStage() {
        return new BinarySerDesStage() {
            @Override
            public byte[] serialize(byte[] value, SerDesContext context) {
                return value;
            }

            @Override
            public byte[] deserialize(byte[] data, SerDesContext context) {
                return data;
            }
        };
    }
}
