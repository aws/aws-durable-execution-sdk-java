// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;

@Timeout(10)
class OwnedExecutorAdmissionFailureTest {
    @ParameterizedTest
    @CsvSource({"0,false", "0,true", "500,false", "500,true"})
    void cutoffBeforeDispatchFinalizesOwnedExecutorOnce(int remainingMillis, boolean plugins) throws Exception {
        var dispatches = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var bodies = new AtomicInteger();
        var starts = new AtomicInteger();
        var ends = new AtomicInteger();
        var owned = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void execute(Runnable command) {
                dispatches.incrementAndGet();
                super.execute(command);
            }

            @Override
            public void shutdown() {
                shutdowns.incrementAndGet();
                super.shutdown();
            }
        };
        var runtime = mock(Context.class);
        when(runtime.getRemainingTimeInMillis()).thenReturn(remainingMillis);
        var builder = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> owned);
        if (plugins)
            builder.withPlugins(ignored -> new DurableExecutionPlugin() {
                public void onInvocationStart(InvocationInfo info) {
                    starts.incrementAndGet();
                }

                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.incrementAndGet();
                }
            });
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
            var failure = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            input,
                            runtime,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                bodies.incrementAndGet();
                                return value;
                            },
                            builder.build()));
            assertAll(
                    () -> assertInstanceOf(UnrecoverableDurableExecutionException.class, failure),
                    () -> assertEquals(0, dispatches.get()),
                    () -> assertEquals(0, bodies.get()),
                    () -> assertEquals(0, starts.get()),
                    () -> assertEquals(0, ends.get()),
                    () -> assertEquals(
                            1, shutdowns.get(), "Synchronous failure must finalize owned resources only once"),
                    () -> assertTrue(owned.isTerminated()));
            assertTrue(((UnrecoverableDurableExecutionException) failure).isRetryable());
        } finally {
            owned.shutdownNow();
            assertTrue(owned.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
