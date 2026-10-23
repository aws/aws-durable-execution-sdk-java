// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.util.ExceptionHelper;

/** The old caller handoff is replaced by handler-owned completion, after the execution cutoff. */
class HandlerScopeHandoffTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectedControlWaitsForScopeOwnerAndEndAfterExecutionCutoff(boolean retry) throws Exception {
        var scopeEntered = new CountDownLatch(1);
        var releaseScope = new CountDownLatch(1);
        var endEntered = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(1);
        var owner = new AtomicReference<Thread>();
        var endThread = new AtomicReference<Thread>();
        var end = new AtomicReference<InvocationEndInfo>();
        var executionManager = new AtomicReference<ExecutionManager>();
        var original = new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorMessage("retry").build(), true);
        var plugin = new ScopedPlugin(
                () -> {
                    owner.set(Thread.currentThread());
                    return () -> {
                        scopeEntered.countDown();
                        await(releaseScope);
                        assertSame(owner.get(), Thread.currentThread());
                    };
                },
                info -> {
                    endThread.set(Thread.currentThread());
                    end.set(info);
                    endEntered.countDown();
                    await(releaseEnd);
                });
        var workers = Executors.newSingleThreadExecutor();
        var callers = Executors.newSingleThreadExecutor();
        var runtime = mock(Context.class);
        when(runtime.getRemainingTimeInMillis()).thenReturn(3000);
        try {
            var config = DurableConfig.builder()
                    .withExecutorService(workers)
                    .withDurableExecutionClient(mock(DurableExecutionClient.class))
                    .withPlugins(info -> plugin)
                    .build();
            var response = callers.submit(() -> DurableExecutor.execute(
                    input(),
                    runtime,
                    TypeToken.get(String.class),
                    (value, context) -> {
                        var manager = ((DurableContextImpl) context).getExecutionManager();
                        executionManager.set(manager);
                        try {
                            if (retry) manager.terminateExecution(original);
                            manager.suspendExecution();
                            return "unreachable";
                        } finally {
                            throw new IllegalStateException("ordinary finally must not replace selected control");
                        }
                    },
                    config));
            assertTrue(scopeEntered.await(3, TimeUnit.SECONDS));
            // Admission must precede the deadline. The selected control may then wait for its real scope
            // owner past the execution cutoff, without consuming the separate cleanup budget.
            var manager = executionManager.get();
            var untilCutoff =
                    manager.getRemainingInvocationTime().orElseThrow().toNanos() - TimeUnit.MILLISECONDS.toNanos(500);
            assertTrue(untilCutoff > 0, "The scope must open before the execution cutoff");
            assertThrows(
                    TimeoutException.class,
                    () -> response.get(untilCutoff + TimeUnit.MILLISECONDS.toNanos(1), TimeUnit.NANOSECONDS));
            assertTrue(manager.getRemainingInvocationTime().orElseThrow().toMillis() < 500);
            assertEquals(1, endEntered.getCount(), "No End before the real scope owner finishes");
            releaseScope.countDown();
            assertTrue(endEntered.await(3, TimeUnit.SECONDS));
            assertSame(owner.get(), endThread.get(), "End belongs to the handler thread");
            assertThrows(TimeoutException.class, () -> response.get(50, TimeUnit.MILLISECONDS));
            releaseEnd.countDown();
            if (retry) {
                var failure = assertThrows(ExecutionException.class, () -> response.get(3, TimeUnit.SECONDS));
                assertSame(original, ExceptionHelper.unwrapAsyncFailure(failure));
                assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
            } else {
                assertEquals(
                        ExecutionStatus.PENDING,
                        response.get(3, TimeUnit.SECONDS).status());
                assertEquals(InvocationStatus.PENDING, end.get().invocationStatus());
            }
        } finally {
            releaseScope.countDown();
            releaseEnd.countDown();
            workers.shutdown();
            callers.shutdown();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
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
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    @HandlerScoped(ScopeOpener.class)
    public static final class ScopedPlugin implements DurableExecutionPlugin {
        private final Supplier<AutoCloseable> scope;
        private final Consumer<InvocationEndInfo> end;

        ScopedPlugin(Supplier<AutoCloseable> scope, Consumer<InvocationEndInfo> end) {
            this.scope = scope;
            this.end = end;
        }

        public void onInvocationEnd(InvocationEndInfo info) {
            end.accept(info);
        }
    }

    public static final class ScopeOpener implements Function<ScopedPlugin, AutoCloseable> {
        public AutoCloseable apply(ScopedPlugin plugin) {
            return plugin.scope.get();
        }
    }
}
