// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.retry.RetryDecision;

class RetrySerDesTest {

    @Test
    void retriesRetryableSerializationFailure() {
        var attempts = new AtomicInteger();
        var delegate = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if (attempts.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("temporary");
                }
                return super.serialize(value);
            }
        };
        var serDes = new RetrySerDes(
                delegate,
                (failure, attempt) -> attempt == 1 ? RetryDecision.retry(Duration.ZERO) : RetryDecision.fail(),
                delay -> {});

        assertEquals("\"value\"", serDes.serialize("value"));
        assertEquals(2, attempts.get());
    }

    @Test
    void doesNotRetryPermanentFailure() {
        var attempts = new AtomicInteger();
        var delegate = new SerDes() {
            @Override
            public String serialize(Object value) {
                attempts.incrementAndGet();
                throw new SerDesException("permanent");
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return null;
            }
        };
        var serDes = new RetrySerDes(delegate, (failure, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});

        assertThrows(SerDesException.class, () -> serDes.serialize("value"));
        assertEquals(1, attempts.get());
    }

    @Test
    void propagatesRetryableFailureWhenStrategyStops() {
        var attempts = new AtomicInteger();
        var delegate = new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                attempts.incrementAndGet();
                throw new RetryableSerDesException("still unavailable");
            }
        };
        var serDes = new RetrySerDes(
                delegate,
                (failure, attempt) -> attempt < 3 ? RetryDecision.retry(Duration.ZERO) : RetryDecision.fail(),
                delay -> {});

        assertThrows(
                RetryableSerDesException.class, () -> serDes.deserialize("\"value\"", TypeToken.get(String.class)));
        assertEquals(3, attempts.get());
    }

    @Test
    void retriesKeepTheSameThreadLocalContext() {
        var attempts = new AtomicInteger();
        var observed = new AtomicReference<SerDesContext>();
        var delegate = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                observed.set(SerDesContext.getCurrentContext());
                if (attempts.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("temporary");
                }
                return super.serialize(value);
            }
        };
        var serDes = new RetrySerDes(delegate, (failure, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});
        var context = new SerDesContext("arn:test", "entity");

        assertEquals("\"value\"", new SerDesRunner(null).serialize(serDes, "value", context));
        assertSame(context, observed.get());
    }

    @Test
    void retriesDeserializationAndUsesStrategyDelay() {
        var attempts = new AtomicInteger();
        var observedDelay = new AtomicReference<Duration>();
        var delegate = new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                if (attempts.incrementAndGet() == 1) {
                    throw new RetryableSerDesException("temporary");
                }
                return super.deserialize(data, typeToken);
            }
        };
        var serDes = new RetrySerDes(
                delegate, (failure, attempt) -> RetryDecision.retry(Duration.ofMillis(25)), observedDelay::set);

        assertEquals("value", serDes.deserialize("\"value\"", TypeToken.get(String.class)));
        assertEquals(Duration.ofMillis(25), observedDelay.get());
        assertEquals(2, attempts.get());
    }

    @Test
    void rejectsInvalidStrategyResults() {
        var failure = new RetryableSerDesException("temporary");
        var delegate = failingSerDes(failure);

        var nullDecision = new RetrySerDes(delegate, (error, attempt) -> null, delay -> {});
        assertTrue(assertThrows(SerDesException.class, () -> nullDecision.serialize("value"))
                .getMessage()
                .contains("returned null"));

        var negativeDelay =
                new RetrySerDes(delegate, (error, attempt) -> RetryDecision.retry(Duration.ofSeconds(-1)), delay -> {});
        assertTrue(assertThrows(SerDesException.class, () -> negativeDelay.serialize("value"))
                .getMessage()
                .contains("invalid delay"));
    }

    @Test
    void restoresInterruptStatusWhenBackoffIsInterrupted() {
        var serDes = new RetrySerDes(
                failingSerDes(new RetryableSerDesException("temporary")),
                (failure, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)),
                delay -> {
                    throw new InterruptedException("stop");
                });

        try {
            assertThrows(SerDesException.class, () -> serDes.serialize("value"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            assertTrue(Thread.interrupted());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    private static SerDes failingSerDes(RuntimeException failure) {
        return new SerDes() {
            @Override
            public String serialize(Object value) {
                throw failure;
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                throw failure;
            }
        };
    }
}
