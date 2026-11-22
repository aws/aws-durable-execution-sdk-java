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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;

@Timeout(20)
class CanonicalLifecycleFailureTest {
    enum Stop {
        INTERRUPT,
        TIMEOUT
    }

    @ParameterizedTest
    @CsvSource({"false,INTERRUPT", "true,INTERRUPT", "false,TIMEOUT", "true,TIMEOUT"})
    void firstLifecycleFailureRemainsCanonicalThroughCleanup(boolean plugins, Stop stop) throws Exception {
        try (var fixture = new Fixture(plugins)) {
            var invocation = fixture.invoke(stop == Stop.TIMEOUT ? 3000 : 60_000);
            assertTrue(fixture.bodyEntered.await(5, TimeUnit.SECONDS));
            if (stop == Stop.INTERRUPT) fixture.caller.get().interrupt();
            assertTrue(fixture.ownerInterrupted.await(5, TimeUnit.SECONDS));
            var first = fixture.manager.get().getLifecycleFailure();
            assertNotNull(first);
            assertEquals(
                    stop == Stop.INTERRUPT
                            ? "Invocation runtime thread was interrupted"
                            : "Invocation deadline reached before execution completed",
                    first.getMessage());
            if (stop == Stop.TIMEOUT) awaitActualCleanup(fixture.caller.get());
            fixture.releaseOwner.countDown();
            var observed = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS))
                    .getCause();
            assertSame(first, observed, "Cleanup must return the same failure already selected for End");
            assertTrue(first.isRetryable());
            if (stop == Stop.INTERRUPT) assertInstanceOf(InterruptedException.class, first.getCause());
            else assertInstanceOf(TimeoutException.class, first.getCause());
            var laterMessage = stop == Stop.INTERRUPT
                    ? "Interrupted while draining invocation tasks"
                    : "Invocation lifecycle did not exit before the cleanup deadline";
            assertTrue(
                    Arrays.stream(first.getSuppressed())
                            .anyMatch(later -> later instanceof UnrecoverableDurableExecutionException
                                    && laterMessage.equals(later.getMessage())),
                    "The later cleanup diagnostic must remain attached to the first lifecycle failure");
            fixture.assertEnded(first, plugins ? InvocationStatus.RETRYING : null);
            assertEquals(stop == Stop.INTERRUPT, fixture.callerInterrupted.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void normalCompletionDoesNotSelectALifecycleFailure(boolean plugins) throws Exception {
        try (var fixture = new Fixture(plugins)) {
            var invocation = fixture.invoke(60_000);
            assertTrue(fixture.bodyEntered.await(5, TimeUnit.SECONDS));
            fixture.releaseOwner.countDown();
            assertEquals(
                    ExecutionStatus.SUCCEEDED,
                    invocation.get(5, TimeUnit.SECONDS).status());
            assertNull(fixture.manager.get().getLifecycleFailure());
            fixture.assertEnded(null, plugins ? InvocationStatus.SUCCEEDED : null);
            assertFalse(fixture.callerInterrupted.get());
        }
    }

    private static void awaitActualCleanup(Thread caller) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Arrays.stream(caller.getStackTrace())
                .noneMatch(frame -> frame.getClassName().equals(ExecutionManager.class.getName())
                        && frame.getMethodName().equals("finishActualInvocationCleanup"))) {
            assertTrue(
                    System.nanoTime() < deadline, "The cancellation allowance did not expire while the owner was held");
            Thread.yield();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final ExecutorService users = Executors.newSingleThreadExecutor();
        private final ExecutorService callers = Executors.newSingleThreadExecutor();
        private final CountDownLatch bodyEntered = new CountDownLatch(1);
        private final CountDownLatch ownerInterrupted = new CountDownLatch(1);
        private final CountDownLatch releaseOwner = new CountDownLatch(1);
        private final AtomicReference<Thread> caller = new AtomicReference<>();
        private final AtomicReference<Thread> owner = new AtomicReference<>();
        private final AtomicReference<ExecutionManager> manager = new AtomicReference<>();
        private final AtomicReference<InvocationEndInfo> end = new AtomicReference<>();
        private final AtomicReference<Thread> endThread = new AtomicReference<>();
        private final AtomicBoolean callerInterrupted = new AtomicBoolean();
        private final AtomicBoolean bodyExited = new AtomicBoolean();
        private final AtomicInteger endCalls = new AtomicInteger();
        private final DurableConfig config;

        private Fixture(boolean plugins) {
            var builder = DurableConfig.builder()
                    .withExecutorService(users)
                    .withDurableExecutionClient(TestUtils.createMockClient());
            if (plugins)
                builder.withPlugins(ignored -> new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationEnd(InvocationEndInfo info) {
                        end.set(info);
                        endThread.set(Thread.currentThread());
                        endCalls.incrementAndGet();
                    }
                });
            config = builder.build();
        }

        private Future<DurableExecutionOutput> invoke(int remainingMillis) {
            var runtime = mock(Context.class);
            when(runtime.getRemainingTimeInMillis()).thenReturn(remainingMillis);
            when(runtime.getAwsRequestId()).thenReturn("canonical-life-request");
            return callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input(),
                            runtime,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                manager.set(((DurableContextImpl) context).getExecutionManager());
                                owner.set(Thread.currentThread());
                                bodyEntered.countDown();
                                try {
                                    awaitOwnerRelease();
                                    return "result";
                                } finally {
                                    bodyExited.set(true);
                                }
                            },
                            config);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
        }

        private void awaitOwnerRelease() {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (true) {
                try {
                    assertTrue(releaseOwner.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                    return;
                } catch (InterruptedException ignored) {
                    ownerInterrupted.countDown();
                }
            }
        }

        private void assertEnded(Throwable failure, InvocationStatus status) {
            assertTrue(bodyExited.get());
            if (status == null) {
                assertEquals(0, endCalls.get());
                return;
            }
            assertEquals(1, endCalls.get());
            assertSame(owner.get(), endThread.get());
            assertEquals(status, end.get().invocationStatus());
            assertSame(failure, end.get().executionError());
        }

        @Override
        public void close() throws InterruptedException {
            releaseOwner.countDown();
            users.shutdownNow();
            callers.shutdownNow();
            assertTrue(users.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:execution/canonical",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("canonical")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
    }
}
