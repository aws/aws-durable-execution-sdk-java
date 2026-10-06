// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;

@Timeout(10)
class InvocationEndFatalDispatchIntegrationTest {
    @SuppressWarnings("removal")
    static Stream<Arguments> failures() {
        return Stream.of(false, true)
                .flatMap(wrapped -> Stream.of(new InternalError("end hook"), new ThreadDeath())
                        .map(fatal -> Arguments.of(wrapped, fatal)));
    }

    static Stream<Arguments> cases() {
        return Stream.of(false, true)
                .flatMap(blocked -> failures().map(args -> Arguments.of(blocked, args.get()[0], args.get()[1])));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void endHookFatalStopsQueuedAtLeastOnceWorkBeforeShutdown(boolean blockedEnd, boolean wrapped, Error fatal)
            throws Exception {
        var executor = new QueuedExecutor();
        var enteredEnd = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(blockedEnd ? 1 : 0);
        var finalizer = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "end-hook-dispatch");
            thread.setDaemon(true);
            return thread;
        });
        var bodies = new AtomicInteger();
        var ends = new ArrayList<InvocationEndInfo>();
        var updates = new CopyOnWriteArrayList<OperationUpdate>();
        DurableExecutionPluginFactory failing = ignored -> new DurableExecutionPlugin() {
            public void onInvocationEnd(InvocationEndInfo info) {
                ends.add(info);
                if (wrapped) throw new CompletionException(new ExecutionException(fatal));
                throw fatal;
            }
        };
        DurableExecutionPluginFactory healthy = ignored -> new DurableExecutionPlugin() {
            public void onInvocationEnd(InvocationEndInfo info) {
                ends.add(info);
                enteredEnd.countDown();
                await(releaseEnd);
            }
        };
        var config = DurableConfig.builder()
                .withExecutorService(executor)
                .withCheckpointDelay(Duration.ZERO)
                .withDurableExecutionClient(new LocalMemoryExecutionClient() {
                    public CheckpointDurableExecutionResponse checkpoint(
                            String arn, String token, List<OperationUpdate> batch) {
                        updates.addAll(batch);
                        return super.checkpoint(arn, token, batch);
                    }
                })
                .withPlugins(failing, healthy)
                .build();
        var input = input();
        var manager = new ExecutionManager(input, config, null);
        try {
            manager.registerActiveThread(null);
            manager.setCurrentThreadContext(new ThreadContext(null, ThreadType.CONTEXT));
            var runner = manager.getPluginRunner();
            runner.onInvocationStart(new InvocationInfo("request", input.durableExecutionArn(), true, Instant.EPOCH));
            var context = DurableContextImpl.createRootContext(manager, config, null);
            BaseContextImpl.setCurrentContext(context);
            context.stepAsync("queued", String.class, step -> {
                bodies.incrementAndGet();
                return "unexpected";
            });
            // Model a handler that returns with accepted async work still waiting for its worker.
            var end = new InvocationEndInfo(
                    "request", input.durableExecutionArn(), true, InvocationStatus.SUCCEEDED, null);
            var dispatch = finalizer.submit(() -> runner.onInvocationEnd(end));
            assertTrue(enteredEnd.await(3, TimeUnit.SECONDS));
            try {
                if (!blockedEnd)
                    assertSame(
                            fatal,
                            assertThrows(ExecutionException.class, () -> dispatch.get(3, TimeUnit.SECONDS))
                                    .getCause());
                else assertFalse(dispatch.isDone(), "the later exporter is still flushing");
                assertSame(fatal, assertThrows(Error.class, executor::runNext));
            } finally {
                releaseEnd.countDown();
                assertSame(
                        fatal,
                        assertThrows(ExecutionException.class, () -> dispatch.get(3, TimeUnit.SECONDS))
                                .getCause());
            }
            assertEquals(List.of(end, end), ends, "remaining plugins retain their single shared end snapshot");
            assertEquals(0, bodies.get(), "invocation-end fatals must stop queued user bodies");
            assertTrue(updates.isEmpty(), "no queued operation checkpoint may reach the backend");
        } finally {
            releaseEnd.countDown();
            finalizer.shutdownNow();
            try {
                manager.close();
            } catch (CompletionException failure) {
                assertSame(fatal, failure.getCause());
            }
            BaseContextImpl.setCurrentContext(null);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("id")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.EPOCH)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        public void execute(Runnable task) {
            tasks.addLast(task);
        }

        void runNext() {
            tasks.removeFirst().run();
        }

        public void shutdown() {}

        public List<Runnable> shutdownNow() {
            return List.of();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
