// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.offload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.exception.RetryablePayloadOffloadException;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.retry.RetryDecision;

class RetryPayloadOffloaderTest {
    @Test
    void retriesRetryableStoreFailure() {
        var attempts = new AtomicInteger();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                if (attempts.incrementAndGet() == 1) {
                    throw new RetryablePayloadOffloadException("transient");
                }
                return OffloadedPayload.inline(serializedPayload);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return payload.data();
            }
        };
        var retrying = new RetryPayloadOffloader(
                offloader,
                (error, attempt) -> attempt < 2 ? RetryDecision.retry(Duration.ZERO) : RetryDecision.fail(),
                delay -> {});

        assertEquals("stored", retrying.offload("stored", context()).data());
        assertEquals(2, attempts.get());
    }

    @Test
    void doesNotRetryPermanentFailure() {
        var attempts = new AtomicInteger();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                attempts.incrementAndGet();
                throw new PayloadOffloadException("permanent");
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return payload.data();
            }
        };
        var retrying = new RetryPayloadOffloader(
                offloader, (error, attempt) -> RetryDecision.retry(Duration.ZERO), delay -> {});

        assertThrows(PayloadOffloadException.class, () -> retrying.offload("stored", context()));
        assertEquals(1, attempts.get());
    }

    @Test
    void retriesRetryableLoadFailure() {
        var attempts = new AtomicInteger();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.inline(serializedPayload);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                if (attempts.incrementAndGet() == 1) {
                    throw new RetryablePayloadOffloadException("transient");
                }
                return payload.data();
            }
        };
        var retrying = new RetryPayloadOffloader(
                offloader,
                (error, attempt) -> attempt < 2 ? RetryDecision.retry(Duration.ZERO) : RetryDecision.fail(),
                delay -> {});

        assertEquals("stored", retrying.load(OffloadedPayload.inline("stored"), context()));
        assertEquals(2, attempts.get());
    }

    @Test
    void interruptedBackoffRemainsRetryableAndRestoresInterruptFlag() {
        Thread.interrupted();
        var initialFailure = new RetryablePayloadOffloadException("transient");
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                throw initialFailure;
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                return payload.data();
            }
        };
        var retrying = new RetryPayloadOffloader(
                offloader, (error, attempt) -> RetryDecision.retry(Duration.ofSeconds(1)), delay -> {
                    throw new InterruptedException("cancelled");
                });

        try {
            var interrupted =
                    assertThrows(RetryablePayloadOffloadException.class, () -> retrying.offload("stored", context()));

            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(interrupted.getCause() instanceof InterruptedException);
            assertEquals(1, interrupted.getSuppressed().length);
            assertSame(initialFailure, interrupted.getSuppressed()[0]);
        } finally {
            Thread.interrupted();
        }
    }

    private static PayloadOffloadContext context() {
        return PayloadOffloadContext.forOperation(
                "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/name/invocation",
                OperationIdentifier.of("op-1", "step", OperationSubType.STEP),
                null,
                SerDesPayloadKind.RESULT,
                1);
    }
}
