// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;

@Timeout(15)
class OwnedExecutorLifecycleIdentityTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ownedTerminationTimeoutDoesNotReplacePreviouslySelectedCutoff(boolean plugins) throws Exception {
        var releaseWorkerTail = new CountDownLatch(1);
        var workerTailEntered = new CountDownLatch(1);
        var first = new AtomicReference<UnrecoverableDurableExecutionException>();
        var end = new AtomicReference<InvocationEndInfo>();
        var stepCalls = new AtomicInteger();
        var owned = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void execute(Runnable command) {
                var commandReturned = new CountDownLatch(1);
                super.execute(() -> {
                    try {
                        command.run();
                    } finally {
                        commandReturned.countDown();
                    }
                });
                // Execute may be synchronous, as with CallerRunsPolicy. Runtime observation starts after the owner.
                awaitIgnoringInterrupt(commandReturned);
            }

            @Override
            protected void afterExecute(Runnable command, Throwable failure) {
                super.afterExecute(command, failure);
                workerTailEntered.countDown();
                // Hold a real executor termination boundary after the tracked SDK task has actually exited.
                awaitIgnoringInterrupt(releaseWorkerTail);
            }
        };
        var runtime = mock(Context.class);
        when(runtime.getRemainingTimeInMillis()).thenReturn(2000);
        var builder = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> owned);
        if (plugins)
            builder.withPlugins(info -> new DurableExecutionPlugin() {
                public void onInvocationEnd(InvocationEndInfo info) {
                    end.set(info);
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
            var observed = assertThrows(
                    UnrecoverableDurableExecutionException.class,
                    () -> DurableExecutor.execute(
                            input,
                            runtime,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                try {
                                    assertFalse(new CountDownLatch(1).await(1600, TimeUnit.MILLISECONDS));
                                } catch (InterruptedException interrupted) {
                                    throw new AssertionError(interrupted);
                                }
                                try {
                                    context.step("expired", String.class, step -> {
                                        stepCalls.incrementAndGet();
                                        return "late";
                                    });
                                } catch (UnrecoverableDurableExecutionException selected) {
                                    first.set(selected);
                                }
                                return "fallback";
                            },
                            builder.build()));
            assertNotNull(first.get());
            assertEquals(
                    "Invocation deadline reached before user work", first.get().getMessage());
            assertSame(
                    first.get(),
                    observed,
                    "Owned termination is a later lifecycle diagnostic, not a replacement outcome");
            assertTrue(Arrays.stream(first.get().getSuppressed())
                    .anyMatch(later -> later instanceof UnrecoverableDurableExecutionException
                            && later.getMessage()
                                    .equals("Invocation executor did not terminate before cleanup deadline")));
            assertTrue(workerTailEntered.await(2, TimeUnit.SECONDS));
            assertFalse(owned.isTerminated(), "The fixture must exercise a real termination timeout");
            assertEquals(0, stepCalls.get());
            if (plugins) {
                assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
                assertSame(first.get(), end.get().executionError());
            }
        } finally {
            releaseWorkerTail.countDown();
            owned.shutdownNow();
            assertTrue(owned.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch gate) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (true) {
            try {
                assertTrue(gate.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                return;
            } catch (InterruptedException ignored) {
            }
        }
    }
}
