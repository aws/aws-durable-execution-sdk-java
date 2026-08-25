// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
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
    void executesInlineWhenNoExecutorIsConfigured() {
        var observedThread = new AtomicReference<Thread>();
        var serDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                observedThread.set(Thread.currentThread());
                return super.serialize(value);
            }
        };

        new SerDesRunner(null).serialize(serDes, "value", context("operation/1/result"));

        assertSame(Thread.currentThread(), observedThread.get());
    }

    @Test
    void restoresPreviousContextAcrossNestedInlineCalls() {
        var runner = new SerDesRunner(null);
        var previous = context("previous");
        var outer = context("outer");
        var inner = context("inner");
        var duringOuter = new AtomicReference<SerDesContext>();
        var afterInner = new AtomicReference<SerDesContext>();
        var innerSerDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                assertSame(inner, SerDesContext.getCurrentContext());
                return super.serialize(value);
            }
        };
        var outerSerDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                duringOuter.set(SerDesContext.getCurrentContext());
                runner.serialize(innerSerDes, value, inner);
                afterInner.set(SerDesContext.getCurrentContext());
                return super.serialize(value);
            }
        };

        SerDesContextHolder.set(previous);
        try {
            runner.serialize(outerSerDes, "value", outer);
            assertSame(previous, SerDesContext.getCurrentContext());
        } finally {
            SerDesContextHolder.clear();
        }

        assertSame(outer, duringOuter.get());
        assertSame(outer, afterInner.get());
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
    void cacheKeyIncludesSerDesIdentity() {
        var runner = new SerDesRunner(null);
        var context = context("operation/1/result");
        var first = fixedValueSerDes("first");
        var second = fixedValueSerDes("second");

        assertEquals("first", runner.deserialize(first, "\"value\"", TypeToken.get(String.class), context));
        assertEquals("second", runner.deserialize(second, "\"value\"", TypeToken.get(String.class), context));
    }

    @Test
    void completedCacheEvictsOldestEntries() {
        var calls = new AtomicInteger();
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return value.toString();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.incrementAndGet();
                return (T) data;
            }
        };
        var runner = new SerDesRunner(null);
        var retainedValues = new ArrayList<String>();

        for (int index = 0; index <= SerDesRunner.MAX_COMPLETED_DESERIALIZATIONS; index++) {
            retainedValues.add(runner.deserialize(
                    serDes, "value-" + index, TypeToken.get(String.class), context("operation/" + index + "/result")));
        }
        assertEquals(SerDesRunner.MAX_COMPLETED_DESERIALIZATIONS + 1, calls.get());

        assertEquals(
                retainedValues.get(0),
                runner.deserialize(serDes, "value-0", TypeToken.get(String.class), context("operation/0/result")));
        assertEquals(SerDesRunner.MAX_COMPLETED_DESERIALIZATIONS + 2, calls.get());
    }

    @Test
    void concurrentCacheMissesDeserializeOnlyOnce() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var count = new AtomicInteger();
        var sharedValue = new Object();
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return value.toString();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                count.incrementAndGet();
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SerDesException("interrupted", e);
                }
                return (T) sharedValue;
            }
        };
        var runner = new SerDesRunner(null);
        var callers = Executors.newFixedThreadPool(8);
        try {
            var futures = new ArrayList<CompletableFuture<Object>>();
            for (int index = 0; index < 8; index++) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> runner.deserialize(
                                serDes, "data", TypeToken.get(Object.class), context("operation/1/result")),
                        callers));
            }
            entered.await();
            release.countDown();

            for (var future : futures) {
                assertSame(sharedValue, future.join());
            }
            assertEquals(1, count.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void failedDeserializationIsRemovedFromCache() {
        var calls = new AtomicInteger();
        var serDes = new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                if (calls.incrementAndGet() == 1) {
                    throw new SerDesException("first");
                }
                return super.deserialize(data, typeToken);
            }
        };
        var runner = new SerDesRunner(null);
        var context = context("operation/1/result");

        assertThrows(
                SerDesException.class,
                () -> runner.deserialize(serDes, "\"value\"", TypeToken.get(String.class), context));
        assertEquals("value", runner.deserialize(serDes, "\"value\"", TypeToken.get(String.class), context));
        assertEquals(2, calls.get());
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

    @Test
    void preservesRetryableFailureType() {
        var runner = new SerDesRunner(null);
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                throw new RetryableSerDesException("transient");
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return null;
            }
        };

        var exception = assertThrows(
                RetryableSerDesException.class, () -> runner.serialize(serDes, "value", context("entity")));

        assertInstanceOf(RetryableSerDesException.class, exception.getCause());
    }

    @Test
    void preservesFatalErrorsWithAndWithoutExecutor() {
        var fatal = new OutOfMemoryError("fatal");
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                throw fatal;
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return null;
            }
        };

        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> new SerDesRunner(null)
                .serialize(serDes, "value", context("entity"))));
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> new SerDesRunner(executor)
                .serialize(serDes, "value", context("entity"))));
    }

    private static SerDes fixedValueSerDes(String value) {
        return new SerDes() {
            @Override
            public String serialize(Object input) {
                return input.toString();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return (T) value;
            }
        };
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
