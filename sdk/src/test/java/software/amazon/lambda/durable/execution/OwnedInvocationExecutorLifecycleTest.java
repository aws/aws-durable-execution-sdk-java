// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;

class OwnedInvocationExecutorLifecycleTest {
    @Test
    void callerShutsDownOwnedExecutorAfterHandlerEndReturns() throws Exception {
        var ownedThreads = ConcurrentHashMap.<Thread>newKeySet();
        var owned = Executors.newFixedThreadPool(2, action -> {
            var thread = new Thread(action, "invocation-owned");
            ownedThreads.add(thread);
            return thread;
        });
        var runtime = Executors.newSingleThreadExecutor();
        var startThread = new AtomicReference<Thread>();
        var stepThread = new AtomicReference<Thread>();
        var endThread = new AtomicReference<Thread>();
        var callerThread = new AtomicReference<Thread>();
        var endEntered = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(1);
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                startThread.set(Thread.currentThread());
            }

            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                endThread.set(Thread.currentThread());
                endEntered.countDown();
                await(releaseEnd);
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> owned)
                .withPlugins(plugin)
                .build();
        try {
            var response = runtime.submit(() -> {
                callerThread.set(Thread.currentThread());
                return DurableExecutor.execute(
                        input(),
                        context(),
                        TypeToken.get(String.class),
                        (value, durable) -> durable.step("step", String.class, step -> {
                            stepThread.set(Thread.currentThread());
                            return value;
                        }),
                        config);
            });
            assertTrue(endEntered.await(5, TimeUnit.SECONDS));
            assertSame(startThread.get(), endThread.get());
            assertTrue(ownedThreads.contains(startThread.get()), "Plugin-enabled roots must use the owned executor");
            assertTrue(ownedThreads.contains(stepThread.get()), "Operation wrappers must use the owned executor");
            assertNotSame(callerThread.get(), endThread.get());
            assertFalse(owned.isShutdown(), "The handler-owned End hook must run before executor shutdown");
            assertFalse(response.isDone(), "The runtime caller must wait for End");
            releaseEnd.countDown();
            assertEquals(
                    ExecutionStatus.SUCCEEDED, response.get(5, TimeUnit.SECONDS).status());
            assertTrue(owned.isTerminated(), "Executor shutdown must not wait for its own root worker");
        } finally {
            releaseEnd.countDown();
            stop(runtime);
            stop(owned);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rootStepAndNestedChildUseTheInvocationSelectedExecutor(boolean pluginsEnabled) throws Exception {
        var ownedThreads = ConcurrentHashMap.<Thread>newKeySet();
        var observedThreads = new CopyOnWriteArrayList<Thread>();
        // Root, child and nested step can wait on each other; give this scoped routing test enough capacity.
        var owned = Executors.newFixedThreadPool(4, action -> {
            var thread = new Thread(action, "routing-owned");
            ownedThreads.add(thread);
            return thread;
        });
        var builder = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> owned);
        if (pluginsEnabled) builder.withPlugins(new DurableExecutionPlugin() {});
        try {
            var response = DurableExecutor.execute(
                    input(),
                    context(),
                    TypeToken.get(String.class),
                    (value, durable) -> {
                        observedThreads.add(Thread.currentThread());
                        durable.step("root-step", String.class, step -> {
                            observedThreads.add(Thread.currentThread());
                            return value;
                        });
                        return durable.runInChildContext("child", String.class, child -> {
                            observedThreads.add(Thread.currentThread());
                            return child.step("nested-step", String.class, step -> {
                                observedThreads.add(Thread.currentThread());
                                return value;
                            });
                        });
                    },
                    builder.build());
            assertEquals(ExecutionStatus.SUCCEEDED, response.status());
            assertEquals("\"input\"", response.result());
            assertEquals(4, observedThreads.size());
            assertTrue(
                    ownedThreads.containsAll(observedThreads),
                    "Root, step, child and nested operation code must all use the invocation-selected executor");
            assertTrue(owned.isTerminated());
        } finally {
            stop(owned);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedRootStillShutsDownInvocationOwnedExecutor(boolean pluginsEnabled) throws Exception {
        var owned = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void execute(Runnable command) {
                throw new RejectedExecutionException("test root submission rejection");
            }
        };
        var builder = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withInvocationExecutorFactory(() -> owned);
        if (pluginsEnabled) builder.withPlugins(new DurableExecutionPlugin() {});
        var config = builder.build();
        try {
            assertThrows(
                    RuntimeException.class,
                    () -> DurableExecutor.execute(
                            input(), context(), TypeToken.get(String.class), (value, durable) -> value, config));
            assertTrue(owned.isTerminated());
        } finally {
            stop(owned);
        }
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.now())
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/execution",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(operation))
                        .build());
    }

    private static Context context() {
        var context = mock(Context.class);
        when(context.getAwsRequestId()).thenReturn("owned-executor-request");
        when(context.getRemainingTimeInMillis()).thenReturn(20_000);
        return context;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
}
