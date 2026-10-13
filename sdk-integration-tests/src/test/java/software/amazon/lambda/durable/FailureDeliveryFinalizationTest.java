// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class FailureDeliveryFinalizationTest {
    @Test
    @Timeout(10)
    void failedResponseMustBePreparedBeforeTerminalEndAndReplaysSavedWork() {
        var rejectError = new AtomicBoolean(true);
        var effects = new AtomicInteger();
        var factories = new AtomicInteger();
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var preparation = new IllegalStateException("error payload could not be encoded");
        SerDes serializer = new SerDes() {
            final JacksonSerDes delegate = new JacksonSerDes();

            public String serialize(Object value) {
                if (value instanceof Throwable && rejectError.get()) throw preparation;
                return delegate.serialize(value);
            }

            public <T> T deserialize(String value, TypeToken<T> type) {
                return delegate.deserialize(value, type);
            }
        };
        var config = DurableConfig.builder()
                .withSerDes(serializer)
                .withPlugins(info -> {
                    factories.incrementAndGet();
                    return new DurableExecutionPlugin() {
                        public void onInvocationEnd(InvocationEndInfo end) {
                            ends.add(end);
                        }
                    };
                })
                .build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    context.step("saved", String.class, step -> {
                        effects.incrementAndGet();
                        return "saved";
                    });
                    throw new IllegalArgumentException("ordinary handler failure");
                },
                config);
        assertSame(preparation, assertThrows(IllegalStateException.class, () -> runner.run("input")));
        assertEquals(1, ends.size());
        assertEquals(InvocationStatus.RETRYING, ends.get(0).invocationStatus());
        assertSame(preparation, ends.get(0).executionError());
        rejectError.set(false);
        assertEquals(ExecutionStatus.FAILED, runner.run("input").getStatus());
        assertEquals(2, factories.get());
        assertEquals(2, ends.size());
        assertEquals(InvocationStatus.FAILED, ends.get(1).invocationStatus());
        assertEquals(1, effects.get());
    }
}
