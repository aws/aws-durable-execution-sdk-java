// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    void executesOnConfiguredExecutorWithThreadLocalContext() {
        var observedThread = new AtomicReference<String>();
        var observedContext = new AtomicReference<SerDesContext>();
        var serDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                observedThread.set(Thread.currentThread().getName());
                observedContext.set(SerDesContext.getCurrentContext());
                return super.serialize(value);
            }
        };
        var context = new SerDesContext("arn:test", "entity");

        assertEquals("\"value\"", runner.serialize(serDes, "value", context));
        assertEquals("test-serdes", observedThread.get());
        assertEquals(context, observedContext.get());
        assertNull(SerDesContext.getCurrentContext());
    }

    @Test
    void executesInlineWhenNoExecutorIsConfigured() {
        var inlineRunner = new SerDesRunner(null);
        var callingThread = Thread.currentThread();
        var observedThread = new AtomicReference<Thread>();
        var context = new SerDesContext("arn:test", "entity");
        var serDes = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                observedThread.set(Thread.currentThread());
                assertEquals(context, SerDesContext.getCurrentContext());
                return super.serialize(value);
            }
        };

        assertEquals("\"value\"", inlineRunner.serialize(serDes, "value", context));
        assertSame(callingThread, observedThread.get());
        assertNull(SerDesContext.getCurrentContext());
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
    void failedDeserializationIsNotCachedAndContextIsCleared() throws Exception {
        var calls = new AtomicInteger();
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return null;
            }

            @Override
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                calls.incrementAndGet();
                assertEquals(new SerDesContext("arn:test", "entity"), SerDesContext.getCurrentContext());
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
        assertNull(executor.submit(SerDesContext::getCurrentContext).get());
        assertTrue(executor.submit(() -> Thread.currentThread().getName().startsWith("test-serdes"))
                .get());
    }

    record Value(String value) {}
}
