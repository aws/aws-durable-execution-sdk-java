// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.*;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class OutputContinuationAdmissionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(10)
    void smallPublicOutputClosesOnlyNewContinuationAdmissionBeforeSerialization(boolean plugins) {
        var manager = new AtomicReference<ExecutionManager>();
        var beforeOutput = new AtomicInteger();
        var duringOutput = new AtomicInteger();
        var serializations = new AtomicInteger();
        var ends = new AtomicInteger();
        SerDes serializer = new SerDes() {
            private final JacksonSerDes delegate = new JacksonSerDes();

            public String serialize(Object value) {
                if ("ROOT-READY".equals(value)) {
                    serializations.incrementAndGet();
                    manager.get()
                            .runCheckpointContinuation(duringOutput::incrementAndGet)
                            .join();
                }
                return delegate.serialize(value);
            }

            public <T> T deserialize(String value, TypeToken<T> type) {
                return delegate.deserialize(value, type);
            }
        };
        var builder = DurableConfig.builder().withSerDes(serializer);
        if (plugins)
            builder.withPlugins(info -> new DurableExecutionPlugin() {
                public void onInvocationEnd(InvocationEndInfo end) {
                    assertEquals(0, duringOutput.get());
                    assertEquals(InvocationStatus.SUCCEEDED, end.invocationStatus());
                    ends.incrementAndGet();
                }
            });
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var actual = ((BaseContextImpl) context).getExecutionManager();
                    manager.set(actual);
                    actual.runCheckpointContinuation(beforeOutput::incrementAndGet)
                            .join();
                    return "ROOT-READY";
                },
                builder.build());
        var result = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ROOT-READY", result.getResult(String.class));
        assertEquals(1, beforeOutput.get());
        assertEquals(1, serializations.get());
        assertEquals(0, duringOutput.get(), "No new coordinator work may be admitted from output serialization");
        assertEquals(plugins ? 1 : 0, ends.get());
    }
}
