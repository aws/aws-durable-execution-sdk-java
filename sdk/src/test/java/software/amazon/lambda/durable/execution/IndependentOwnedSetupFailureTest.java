// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;

class IndependentOwnedSetupFailureTest {
    @SuppressWarnings("removal")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void headerFatalRetainsIdentityWhenOwnedCloseReportsTheSameFatal(boolean death) throws Exception {
        Error failure = death ? new ThreadDeath() : new OutOfMemoryError("simulated reused VM fatal");
        var shutdownCalls = new AtomicInteger();
        var bodies = new AtomicInteger();
        var owned = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void shutdown() {
                shutdownCalls.incrementAndGet();
                super.shutdown();
                throw failure;
            }
        };
        var runtime = mock(RuntimeContext.class);
        when(runtime.getRemainingTimeInMillis()).thenReturn(10_000);
        when(runtime.getXrayTraceId()).thenThrow(failure);
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> owned)
                .withPlugins(ignored -> new DurableExecutionPlugin() {})
                .build();
        var input = new DurableExecutionInput(
                "arn:execution/test",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("test")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
        try {
            var observed = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            input,
                            runtime,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                bodies.incrementAndGet();
                                return value;
                            },
                            config));
            assertSame(failure, observed, "Implicit close must not self-suppress a fatal raised before dispatch");
            assertEquals(0, bodies.get());
            assertEquals(1, shutdownCalls.get());
            assertTrue(owned.isTerminated());
        } finally {
            owned.shutdownNow();
            assertTrue(owned.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private abstract static class RuntimeContext implements Context {
        @Override
        public String getXrayTraceId() {
            return null;
        }
    }
}
