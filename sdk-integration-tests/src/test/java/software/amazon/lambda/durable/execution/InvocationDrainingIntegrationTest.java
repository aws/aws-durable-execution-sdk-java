// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class InvocationDrainingIntegrationTest {
    @ParameterizedTest
    @CsvSource({"false, FLAT", "false, NESTED", "true, FLAT", "true, NESTED"})
    void handlerReturnDrainsQueuedBranches(boolean parallel, NestingType nestingType) throws Exception {
        var users = Executors.newCachedThreadPool();
        var runtime = Executors.newSingleThreadExecutor();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var manager = new AtomicReference<ExecutionManager>();
        var completed = new CopyOnWriteArrayList<String>();
        var config = DurableConfig.builder().withExecutorService(users).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    manager.set(((DurableContextImpl) context).getExecutionManager());
                    startQueuedWork(context, parallel, nestingType, item -> {
                        if (item.equals("a")) {
                            firstStarted.countDown();
                            await(releaseFirst);
                        }
                        completed.add(item);
                        return item;
                    });
                    await(firstStarted);
                    return "done";
                },
                config);

        try {
            var invocation = runtime.submit(() -> runner.runUntilComplete("input"));
            await(firstStarted);
            awaitDraining(manager.get());
            releaseFirst.countDown();
            var result = invocation.get(10, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            assertEquals("done", result.getResult(String.class));
            assertEquals(List.of("a", "b", "c"), completed);
            assertEquals(
                    OperationStatus.SUCCEEDED,
                    result.getOperation("queued-work").getStatus());
        } finally {
            releaseFirst.countDown();
            stop(runtime);
            stop(users);
        }
    }

    private static void startQueuedWork(
            DurableContext context, boolean parallel, NestingType nestingType, Function<String, String> action) {
        // Default allCompleted requires a caller to join. Use an explicit threshold to complete without joining.
        var completion = CompletionConfig.minSuccessful(3);
        if (parallel) {
            var operation = context.parallel(
                    "queued-work",
                    ParallelConfig.builder()
                            .maxConcurrency(1)
                            .nestingType(nestingType)
                            .completionConfig(completion)
                            .build());
            for (var item : List.of("a", "b", "c")) {
                operation.branch(item, String.class, child -> action.apply(item));
            }
        } else {
            context.mapAsync(
                    "queued-work",
                    List.of("a", "b", "c"),
                    String.class,
                    (item, index, child) -> action.apply(item),
                    MapConfig.builder()
                            .maxConcurrency(1)
                            .nestingType(nestingType)
                            .completionConfig(completion)
                            .build());
        }
    }

    private static void awaitDraining(ExecutionManager manager) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (manager.getLifecycleState() != ExecutionManager.LifecycleState.DRAINING) {
            assertTrue(System.nanoTime() < deadline, "Invocation did not begin draining");
            Thread.yield();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Test coordination timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void stop(ExecutorService executor) throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
}
