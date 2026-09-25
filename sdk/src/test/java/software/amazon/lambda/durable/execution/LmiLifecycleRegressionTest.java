// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static software.amazon.lambda.durable.model.ExecutionStatus.PENDING;
import static software.amazon.lambda.durable.model.ExecutionStatus.SUCCEEDED;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
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

/** Local red regressions for #726. Real LMI validation lives in lmi-tests; these do not claim cloud coverage. */
@EnabledIfSystemProperty(named = "test.lmi.regressions.enabled", matches = "true")
class LmiLifecycleRegressionTest {
    @Test
    void pendingMustWaitForRootFinally() throws Exception {
        var cleanupEntered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var users = Executors.newCachedThreadPool();
        var runtime = Executors.newSingleThreadExecutor();
        try {
            var response = runtime.submit(() -> DurableExecutor.execute(
                    input("pending"),
                    context(10_000),
                    TypeToken.get(String.class),
                    (value, ctx) -> {
                        try {
                            ctx.wait("wait", Duration.ofSeconds(5));
                            return value;
                        } finally {
                            cleanupEntered.countDown();
                            await(release);
                        }
                    },
                    config(users)));
            await(cleanupEntered);
            assertThrows(
                    TimeoutException.class,
                    () -> response.get(250, TimeUnit.MILLISECONDS),
                    "PENDING must not precede the root handler's finally exit");
            release.countDown();
            assertEquals(PENDING, response.get(3, TimeUnit.SECONDS).status());
        } finally {
            release.countDown();
            stop(users, runtime);
        }
    }

    @Test
    void deadlineMustCancelAnInvocationOwnedTaskAndBoundCleanup() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var users = Executors.newCachedThreadPool();
        var runtime = Executors.newSingleThreadExecutor();
        try {
            runtime.submit(() -> DurableExecutor.execute(
                    input("deadline"),
                    context(1000),
                    TypeToken.get(String.class),
                    (value, ctx) -> {
                        ctx.stepAsync("blocked", String.class, step -> {
                            entered.countDown();
                            try {
                                await(release);
                                return value;
                            } finally {
                                exited.countDown();
                            }
                        });
                        await(entered);
                        return value;
                    },
                    config(users)));
            await(entered);
            assertTrue(exited.await(3, TimeUnit.SECONDS), "Task outlived the invocation deadline without cancellation");
        } finally {
            release.countDown();
            stop(users, runtime);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sharedFixedExecutorMustProgress(boolean nested) throws Exception {
        var entered = new CountDownLatch(2);
        var users = (ThreadPoolExecutor) Executors.newFixedThreadPool(2);
        var runtime = Executors.newFixedThreadPool(2);
        try {
            var config = config(users);
            var responses = IntStream.range(0, 2)
                    .mapToObj(index -> runtime.submit(() -> DurableExecutor.execute(
                            input("fixed-" + index),
                            context(10_000),
                            TypeToken.get(String.class),
                            (value, ctx) -> {
                                entered.countDown();
                                await(entered);
                                if (nested) {
                                    return ctx.runInChildContext(
                                            "child",
                                            String.class,
                                            child -> child.runInChildContext(
                                                    "grandchild",
                                                    String.class,
                                                    grandchild ->
                                                            grandchild.step("step", String.class, step -> value)));
                                }
                                return ctx.step("step", String.class, step -> value);
                            },
                            config)))
                    .toList();
            await(entered);
            for (var response : responses) {
                assertEquals(
                        SUCCEEDED,
                        assertDoesNotThrow(
                                        () -> response.get(2, TimeUnit.SECONDS),
                                        "Shared executor progress deadline exceeded")
                                .status(),
                        "Blocking orchestration must not starve the work it awaits");
            }
        } finally {
            users.setMaximumPoolSize(16);
            users.setCorePoolSize(16); // Escape only after the assertion, so the test process cannot hang.
            stop(users, runtime);
        }
    }

    private static DurableConfig config(ExecutorService users) {
        return DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withExecutorService(users)
                .build();
    }

    private static Context context(int milliseconds) {
        var context = mock(Context.class);
        var deadline = System.nanoTime() + milliseconds * 1_000_000L;
        when(context.getAwsRequestId()).thenReturn("test-request");
        when(context.getRemainingTimeInMillis()).thenAnswer(call -> (int) ((deadline - System.nanoTime()) / 1_000_000));
        return context;
    }

    private static DurableExecutionInput input(String name) {
        var operation = Operation.builder()
                .id(name)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/" + name,
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(List.of(operation))
                        .build());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(8, TimeUnit.SECONDS), "Fixture coordination exceeded its bound");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void stop(ExecutorService users, ExecutorService runtime) throws InterruptedException {
        runtime.shutdown();
        assertTrue(runtime.awaitTermination(10, TimeUnit.SECONDS));
        users.shutdown();
        assertTrue(users.awaitTermination(10, TimeUnit.SECONDS));
    }
}
