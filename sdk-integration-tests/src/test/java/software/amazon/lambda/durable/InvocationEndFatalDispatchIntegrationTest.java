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
import java.util.concurrent.ExecutionException;
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

    @ParameterizedTest
    @MethodSource("failures")
    void endHookFatalStopsQueuedAtLeastOnceWorkBeforeShutdown(boolean wrapped, Error fatal) {
        var executor = new QueuedExecutor();
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
        try (var manager = new ExecutionManager(input, config, null)) {
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
            assertSame(fatal, assertThrows(Error.class, () -> runner.onInvocationEnd(end)));
            assertEquals(List.of(end, end), ends, "remaining plugins retain their single shared end snapshot");
            // Release accepted work only after the end dispatch has returned exceptionally.
            assertSame(fatal, assertThrows(Error.class, executor::runNext));
            assertEquals(0, bodies.get(), "invocation-end fatals must stop queued user bodies");
            assertTrue(updates.isEmpty(), "no queued operation checkpoint may reach the backend");
        } finally {
            BaseContextImpl.setCurrentContext(null);
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
