// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.OperationSubType;

class SerDesRunnerTest {
    private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var thread = new Thread(r);
        thread.setName("test-serdes");
        return thread;
    });

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void setsContextInsideExecutorAndClearsItAfterCall() throws Exception {
        var observedContext = new AtomicReference<SerDesContext>();
        var observedThread = new AtomicReference<String>();
        var serDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                observedContext.set(SerDesContext.getCurrentContext());
                observedThread.set(Thread.currentThread().getName());
                return super.serialize(value);
            }
        };
        var context = context("operation/1/result");

        new SerDesRunner(executor).serialize(serDes, "value", context);

        assertSame(context, observedContext.get());
        assertEquals("test-serdes", observedThread.get());
        assertNull(executor.submit(SerDesContext::getCurrentContext).get());
        assertNull(SerDesContext.getCurrentContext());
    }

    @Test
    void cachesByEntityTypeAndSerializedDataHash() {
        var count = new AtomicInteger();
        var delegate = new JacksonSerDes();
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return delegate.serialize(value);
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                count.incrementAndGet();
                return delegate.deserialize(data, typeToken);
            }
        };
        var runner = new SerDesRunner(executor);
        var context = context("operation/1/result");

        assertEquals("one", runner.deserialize(serDes, "\"one\"", TypeToken.get(String.class), context));
        assertEquals("one", runner.deserialize(serDes, "\"one\"", TypeToken.get(String.class), context));
        assertEquals("two", runner.deserialize(serDes, "\"two\"", TypeToken.get(String.class), context));
        var nextAttempt = new SerDesContext(
                context.durableExecutionArn(),
                context.entityId(),
                context.payloadKind(),
                context.operationId(),
                context.operationName(),
                context.parentId(),
                context.operationType(),
                context.operationSubType(),
                2);
        assertEquals("one", runner.deserialize(serDes, "\"one\"", TypeToken.get(String.class), nextAttempt));

        assertEquals(3, count.get());
    }

    @Test
    void wrapsFailuresWithPayloadMetadata() {
        var runner = new SerDesRunner(executor);
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                throw new IllegalStateException("boom");
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return null;
            }
        };

        var exception = assertThrows(SerDesException.class, () -> runner.serialize(serDes, "value", context("entity")));

        assertTrue(exception.getMessage().contains("RESULT"));
        assertTrue(exception.getMessage().contains("entity"));
        assertEquals("boom", exception.getCause().getMessage());
    }

    private static SerDesContext context(String entityId) {
        return new SerDesContext(
                "arn:test",
                entityId,
                SerDesPayloadKind.RESULT,
                "1",
                "step",
                null,
                OperationType.STEP,
                OperationSubType.STEP,
                1);
    }
}
