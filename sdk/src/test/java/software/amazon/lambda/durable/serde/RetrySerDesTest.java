// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
