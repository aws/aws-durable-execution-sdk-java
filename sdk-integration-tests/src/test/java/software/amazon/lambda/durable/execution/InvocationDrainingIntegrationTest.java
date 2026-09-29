// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
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
    @CsvSource({
        "false, FLAT, false", "false, NESTED, false", "true, FLAT, false", "true, NESTED, false",
        "false, FLAT, true", "false, NESTED, true", "true, FLAT, true", "true, NESTED, true"
    })
    void handlerReturnDrainsQueuedBranches(boolean parallel, NestingType nestingType, boolean nestedSteps)
            throws Exception {
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
                    startQueuedWork(context, parallel, nestingType, (item, child) -> {
                        if (item.equals("a")) {
                            firstStarted.countDown();
                            await(releaseFirst);
                        }
                        if (nestedSteps) {
                            return child.step("nested-step", String.class, step -> {
                                completed.add(item);
                                return item;
                            });
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
            if (nestedSteps) {
                var steps = result.getOperations().stream()
                        .filter(op -> "nested-step".equals(op.getName()))
                        .toList();
                assertEquals(3, steps.size());
                assertTrue(steps.stream().allMatch(op -> op.getStatus() == OperationStatus.SUCCEEDED));
                var replay = runner.runUntilComplete("input");
                assertEquals(ExecutionStatus.SUCCEEDED, replay.getStatus());
                assertEquals(List.of("a", "b", "c"), completed, "Replay must not repeat nested step bodies");
                assertEquals(
                        steps.stream().map(op -> op.getId()).toList(),
                        replay.getOperations().stream()
                                .filter(op -> "nested-step".equals(op.getName()))
                                .map(op -> op.getId())
                                .toList());
            }
        } finally {
            releaseFirst.countDown();
            stop(runtime);
            stop(users);
        }
    }

    @Test
    void drainsAsyncDescendantsCreatedAfterTheRootReturns() throws Exception {
        var users = Executors.newCachedThreadPool();
        var runtime = Executors.newSingleThreadExecutor();
        var childEntered = new CountDownLatch(1);
        var releaseChild = new CountDownLatch(1);
        var leafEntered = new CountDownLatch(1);
        var releaseLeaf = new CountDownLatch(1);
        var effects = new AtomicInteger();
        var manager = new AtomicReference<ExecutionManager>();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    manager.set(((DurableContextImpl) context).getExecutionManager());
                    context.runInChildContextAsync("child", String.class, child -> {
                        childEntered.countDown();
                        await(releaseChild);
                        child.runInChildContextAsync("grandchild", String.class, grandchild -> {
                            grandchild.stepAsync("leaf", String.class, step -> {
                                leafEntered.countDown();
                                await(releaseLeaf);
                                effects.incrementAndGet();
                                return "leaf-result";
                            });
                            return "grandchild-result";
                        });
                        return "child-result";
                    });
                    await(childEntered);
                    return "done";
                },
                DurableConfig.builder().withExecutorService(users).build());
        try {
            var invocation = runtime.submit(() -> runner.runUntilComplete("input"));
            await(childEntered);
            awaitDraining(manager.get());
            releaseChild.countDown();
            await(leafEntered);
            assertFalse(invocation.isDone(), "Shutdown must wait for the newly registered async leaf");
            releaseLeaf.countDown();
            var result = invocation.get(10, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            assertEquals(OperationStatus.SUCCEEDED, result.getOperation("leaf").getStatus());
            assertEquals("leaf-result", result.getOperation("leaf").getStepResult(String.class));
            var replay = runner.runUntilComplete("input");
            assertEquals(ExecutionStatus.SUCCEEDED, replay.getStatus());
            assertEquals(1, effects.get(), "The checkpointed descendant must not repeat on replay");
        } finally {
            releaseChild.countDown();
            releaseLeaf.countDown();
            stop(runtime);
            stop(users);
        }
    }

    private static void startQueuedWork(
            DurableContext context,
            boolean parallel,
            NestingType nestingType,
            BiFunction<String, DurableContext, String> action) {
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
                operation.branch(item, String.class, child -> action.apply(item, child));
            }
        } else {
            context.mapAsync(
                    "queued-work",
                    List.of("a", "b", "c"),
                    String.class,
                    (item, index, child) -> action.apply(item, child),
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
