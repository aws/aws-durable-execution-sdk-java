// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;

class SerDesRunnerTest {
    private ExecutorService executor;
    private SerDesRunner runner;

    @BeforeEach
    void setUp() {
        executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "test-serdes"));
        runner = new SerDesRunner(executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void executesOnConfiguredExecutorWithExplicitContext() {
        var observedThread = new AtomicReference<String>();
        var observedContext = new AtomicReference<SerDesContext>();
        var serDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value, SerDesContext context) {
                observedThread.set(Thread.currentThread().getName());
                observedContext.set(context);
                return super.serialize(value);
            }
        };
        var context = new SerDesContext("arn:test", "entity");

        assertEquals("\"value\"", runner.serialize(serDes, "value", context));
        assertEquals("test-serdes", observedThread.get());
        assertEquals(context, observedContext.get());
    }

    @Test
    void executesInlineWhenNoExecutorIsConfigured() {
        var inlineRunner = new SerDesRunner(null);
        var callingThread = Thread.currentThread();
        var observedThread = new AtomicReference<Thread>();
        var context = new SerDesContext("arn:test", "entity");
        var serDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value, SerDesContext suppliedContext) {
                observedThread.set(Thread.currentThread());
                assertEquals(context, suppliedContext);
                return super.serialize(value);
            }
        };

        assertEquals("\"value\"", inlineRunner.serialize(serDes, "value", context));
        assertSame(callingThread, observedThread.get());
    }

    @Test
    void cachesSuccessfulDeserializationForInvocation() {
        var calls = new AtomicInteger();
        var serDes = new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.incrementAndGet();
                return super.deserialize(data, typeToken);
            }
        };
        var context = new SerDesContext("arn:test", "entity");

        var first = runner.deserialize(serDes, "{\"value\":\"cached\"}", TypeToken.get(Value.class), context);
        var second = runner.deserialize(serDes, "{\"value\":\"cached\"}", TypeToken.get(Value.class), context);

        assertSame(first, second);
        assertEquals(1, calls.get());
    }

    @Test
    void cacheKeyIncludesSerializedPayload() {
        var calls = new AtomicInteger();
        var serDes = new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.incrementAndGet();
                return super.deserialize(data, typeToken);
            }
        };
        var context = new SerDesContext("arn:test", "entity");

        runner.deserialize(serDes, "{\"value\":\"one\"}", TypeToken.get(Value.class), context);
        runner.deserialize(serDes, "{\"value\":\"two\"}", TypeToken.get(Value.class), context);

        assertEquals(2, calls.get());
    }

    @Test
    void serializationInvalidatesStableExternalReferenceCache() {
        var storage = new ConcurrentHashMap<String, String>();
        var calls = new AtomicInteger();
        var delegate = new JacksonSerDes();
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                storage.put("stable", delegate.serialize(value));
                return "reference:stable";
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.incrementAndGet();
                return delegate.deserialize(storage.get("stable"), typeToken);
            }
        };
        var context = new SerDesContext("arn:test", "entity");

        var firstReference = runner.serialize(serDes, new Value("one"), context);
        var first = runner.deserialize(serDes, firstReference, TypeToken.get(Value.class), context);
        var secondReference = runner.serialize(serDes, new Value("two"), context);
        var second = runner.deserialize(serDes, secondReference, TypeToken.get(Value.class), context);

        assertEquals(firstReference, secondReference);
        assertEquals(new Value("one"), first);
        assertEquals(new Value("two"), second);
        assertEquals(2, calls.get());
    }

    @Test
    void failedDeserializationIsNotCachedAndContextIsCleared() throws Exception {
        var calls = new AtomicInteger();
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return null;
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                throw new AssertionError("context-aware overload should be used");
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken, SerDesContext context) {
                calls.incrementAndGet();
                assertEquals(new SerDesContext("arn:test", "entity"), context);
                throw new IllegalStateException("failed");
            }
        };
        var context = new SerDesContext("arn:test", "entity");

        assertThrows(
                IllegalStateException.class,
                () -> runner.deserialize(serDes, "\"value\"", TypeToken.get(String.class), context));
        assertThrows(
                IllegalStateException.class,
                () -> runner.deserialize(serDes, "\"value\"", TypeToken.get(String.class), context));

        assertEquals(2, calls.get());
        assertTrue(executor.submit(() -> Thread.currentThread().getName().startsWith("test-serdes"))
                .get());
    }

    @Test
    void cacheKeyIncludesSerDesIdentity() {
        var calls = new AtomicInteger();
        var first = countingSerDes(calls);
        var second = countingSerDes(calls);
        var context = new SerDesContext("arn:test", "entity");

        runner.deserialize(first, "{\"value\":\"same\"}", TypeToken.get(Value.class), context);
        runner.deserialize(second, "{\"value\":\"same\"}", TypeToken.get(Value.class), context);

        assertEquals(2, calls.get());
    }

    @Test
    void completedCacheEvictsLeastRecentlyUsedEntries() {
        var calls = new AtomicInteger();
        var serDes = countingSerDes(calls);
        var values = new java.util.ArrayList<Value>();
        for (int index = 0; index <= SerDesRunner.MAX_COMPLETED_DESERIALIZATIONS; index++) {
            values.add(runner.deserialize(
                    serDes,
                    "{\"value\":\"" + index + "\"}",
                    TypeToken.get(Value.class),
                    new SerDesContext("arn:test", "entity-" + index)));
        }

        runner.deserialize(
                serDes, "{\"value\":\"0\"}", TypeToken.get(Value.class), new SerDesContext("arn:test", "entity-0"));

        assertEquals(SerDesRunner.MAX_COMPLETED_DESERIALIZATIONS + 2, calls.get());
        assertEquals(SerDesRunner.MAX_COMPLETED_DESERIALIZATIONS + 1, values.size());
    }

    @Test
    void concurrentCacheMissesDeserializeOnlyOnce() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var serDes = new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.incrementAndGet();
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return super.deserialize(data, typeToken);
            }
        };
        var context = new SerDesContext("arn:test", "entity");
        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(
                    () -> runner.deserialize(serDes, "{\"value\":\"x\"}", TypeToken.get(Value.class), context));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var second = callers.submit(
                    () -> runner.deserialize(serDes, "{\"value\":\"x\"}", TypeToken.get(Value.class), context));
            release.countDown();

            assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void cachesNullDeserializationResults() {
        var calls = new AtomicInteger();
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return null;
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.incrementAndGet();
                return null;
            }
        };
        var context = new SerDesContext("arn:test", "entity");

        assertNull(runner.deserialize(serDes, null, TypeToken.get(String.class), context));
        assertNull(runner.deserialize(serDes, null, TypeToken.get(String.class), context));
        assertEquals(1, calls.get());
    }

    @Test
    void preservesFatalErrorsWithAndWithoutExecutor() {
        var fatal = new AssertionError("fatal");
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                throw fatal;
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                throw fatal;
            }
        };
        var context = new SerDesContext("arn:test", "entity");

        assertSame(fatal, assertThrows(AssertionError.class, () -> runner.serialize(serDes, "value", context)));
        assertSame(fatal, assertThrows(AssertionError.class, () -> new SerDesRunner(null)
                .deserialize(serDes, "\"value\"", TypeToken.get(String.class), context)));
    }

    private static SerDes countingSerDes(AtomicInteger calls) {
        return new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.incrementAndGet();
                return super.deserialize(data, typeToken);
            }
        };
    }

    record Value(String value) {}
}
