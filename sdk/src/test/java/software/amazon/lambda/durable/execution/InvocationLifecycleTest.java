// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static software.amazon.lambda.durable.TypeToken.get;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.slf4j.helpers.BasicMDCAdapter;
import org.slf4j.spi.MDCAdapter;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class InvocationLifecycleTest {
    private MDCAdapter originalMdcAdapter;

    @BeforeEach
    void installMdcAdapter() throws ReflectiveOperationException {
        originalMdcAdapter = MDC.getMDCAdapter();
        setMdcAdapter(new BasicMDCAdapter());
    }

    @AfterEach
    void restoreMdcAdapter() throws ReflectiveOperationException {
        MDC.clear();
        BaseContextImpl.setCurrentContext(null);
        setMdcAdapter(originalMdcAdapter);
    }

    private static void setMdcAdapter(MDCAdapter adapter) throws ReflectiveOperationException {
        var field = MDC.class.getDeclaredField("MDC_ADAPTER");
        field.setAccessible(true);
        field.set(null, adapter);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void precompletedHandlerStillEndsOnItsOwner(boolean direct) throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var plugin = new RecordingPlugin();
        var handlerThread = new AtomicReference<Thread>();
        var executor = executor(task -> {
            if (direct) task.run();
            else awaitTask(workers, task);
        });
        try {
            var output = DurableExecutor.execute(
                    input(),
                    null,
                    get(String.class),
                    (input, context) -> {
                        handlerThread.set(Thread.currentThread());
                        return "done";
                    },
                    config(executor, plugin));
            assertEquals(ExecutionStatus.SUCCEEDED, output.status());
            assertEquals(1, plugin.starts.size());
            assertEquals(1, plugin.ends.size());
            assertSame(handlerThread.get(), plugin.startThreads.get(0));
            assertSame(handlerThread.get(), plugin.endThreads.get(0));
            assertEquals("invocation", plugin.endLocalValues.get(0));
            if (!direct) assertNotSame(Thread.currentThread(), handlerThread.get());
        } finally {
            stop(workers);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reusedWorkerRestoresAmbientMdcAfterBothHooks(boolean fails) throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var plugin = new RecordingPlugin();
        var ambient = Map.of("worker", "ambient");
        var callerMdc = MDC.getCopyOfContextMap();
        MDC.put("caller", "untouched");
        try {
            workers.submit(() -> MDC.setContextMap(ambient)).get(5, TimeUnit.SECONDS);
            var config = config(workers, plugin);
            for (int invocation = 0; invocation < 2; invocation++) {
                var output = DurableExecutor.execute(
                        input(),
                        null,
                        get(String.class),
                        (input, context) -> {
                            if (fails) throw new IllegalStateException("handler failure");
                            return "done";
                        },
                        config);
                assertEquals(fails ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED, output.status());
                assertEquals(ambient, workers.submit(MDC::getCopyOfContextMap).get(5, TimeUnit.SECONDS));
                assertNull(workers.submit(plugin.local::get).get(5, TimeUnit.SECONDS));
                assertEquals("untouched", MDC.get("caller"));
            }
            assertEquals(2, plugin.ends.size());
            assertEquals(List.of("invocation", "invocation"), plugin.endLocalValues);
            assertEquals(plugin.startThreads, plugin.endThreads);
            assertEquals(List.of(ambient, ambient), plugin.startMdc);
        } finally {
            if (callerMdc == null) MDC.clear();
            else MDC.setContextMap(callerMdc);
            stop(workers);
        }
    }

    @Test
    void inputDeserializationFailurePairsHooksOnWorkerWithoutRunningHandler() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var plugin = new RecordingPlugin();
        var handlerCalled = new AtomicBoolean();
        var serDes = mock(SerDes.class);
        when(serDes.deserialize(any(), any())).thenThrow(new IllegalArgumentException("invalid input"));
        var config = config(workers, plugin).toBuilder().withSerDes(serDes).build();
        try {
            var output = DurableExecutor.execute(
                    input(),
                    null,
                    get(String.class),
                    (input, context) -> {
                        handlerCalled.set(true);
                        return "unreachable";
                    },
                    config);
            assertEquals(ExecutionStatus.FAILED, output.status());
            assertFalse(handlerCalled.get());
            assertEquals(1, plugin.starts.size());
            assertNull(plugin.starts.get(0).executionInput());
            assertEquals(1, plugin.ends.size());
            assertEquals(InvocationStatus.FAILED, plugin.ends.get(0).invocationStatus());
            assertSame(plugin.startThreads.get(0), plugin.endThreads.get(0));
            assertNotSame(Thread.currentThread(), plugin.endThreads.get(0));
            assertEquals(List.of("invocation"), plugin.endLocalValues);
        } finally {
            stop(workers);
        }
    }

    @Test
    void mdcCaptureFailureBeforeStartDoesNotDispatchEitherHook() throws Exception {
        var workers = Executors.newSingleThreadExecutor();
        var plugin = new RecordingPlugin();
        var handlerCalled = new AtomicBoolean();
        var executor = executor(task -> workers.execute(() -> {
            try (var mdc = mockStatic(MDC.class, CALLS_REAL_METHODS)) {
                mdc.when(MDC::getCopyOfContextMap).thenThrow(new IllegalStateException("capture failed"));
                task.run();
            }
        }));
        try {
            var output = DurableExecutor.execute(
                    input(),
                    null,
                    get(String.class),
                    (input, context) -> {
                        handlerCalled.set(true);
                        return "unreachable";
                    },
                    config(executor, plugin));
            assertEquals(ExecutionStatus.FAILED, output.status());
            assertFalse(handlerCalled.get());
            assertTrue(plugin.starts.isEmpty());
            assertTrue(plugin.ends.isEmpty());
        } finally {
            stop(workers);
        }
    }

    @Test
    void callerCannotReturnUntilInvocationEndCompletes() throws Exception {
        var enteredEnd = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(1);
        var plugin = new RecordingPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                enteredEnd.countDown();
                await(releaseEnd);
                super.onInvocationEnd(info);
            }
        };
        var callers = Executors.newSingleThreadExecutor();
        var workers = Executors.newSingleThreadExecutor();
        try {
            var response = callers.submit(() -> DurableExecutor.execute(
                    input(), null, get(String.class), (input, context) -> "done", config(workers, plugin)));
            assertTrue(enteredEnd.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> response.get(700, TimeUnit.MILLISECONDS));
            releaseEnd.countDown();
            assertEquals(
                    ExecutionStatus.SUCCEEDED, response.get(5, TimeUnit.SECONDS).status());
            assertEquals(1, plugin.ends.size());
            assertEquals(plugin.startThreads, plugin.endThreads);
        } finally {
            releaseEnd.countDown();
            stop(callers);
            stop(workers);
        }
    }

    @Test
    void callerCannotReturnUntilAmbientMdcIsRestored() throws Exception {
        var restoring = new CountDownLatch(1);
        var releaseRestore = new CountDownLatch(1);
        var restored = new AtomicBoolean();
        var ambient = Map.of("worker", "ambient");
        var workers = Executors.newSingleThreadExecutor();
        var callers = Executors.newSingleThreadExecutor();
        var plugin = new RecordingPlugin();
        var executor = executor(task -> workers.execute(() -> {
            MDC.setContextMap(ambient);
            try (var mdc = mockStatic(MDC.class, CALLS_REAL_METHODS)) {
                mdc.when(() -> MDC.setContextMap(ambient)).thenAnswer(call -> {
                    restoring.countDown();
                    await(releaseRestore);
                    call.callRealMethod();
                    restored.set(true);
                    return null;
                });
                task.run();
            }
        }));
        try {
            var response = callers.submit(() -> DurableExecutor.execute(
                    input(), null, get(String.class), (input, context) -> "done", config(executor, plugin)));
            assertTrue(restoring.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> response.get(700, TimeUnit.MILLISECONDS));
            releaseRestore.countDown();
            assertEquals(
                    ExecutionStatus.SUCCEEDED, response.get(5, TimeUnit.SECONDS).status());
            assertTrue(restored.get());
            assertEquals(1, plugin.ends.size());
        } finally {
            releaseRestore.countDown();
            stop(callers);
            stop(workers);
        }
    }

    @Test
    void serializationFailureStillEndsOnTheHandlerThread() throws Exception {
        var failure = new IllegalStateException("output cannot serialize");
        var serDes = spy(new JacksonSerDes());
        when(serDes.serialize("done")).thenThrow(failure);
        var plugin = new RecordingPlugin();
        var workers = Executors.newSingleThreadExecutor();
        var config = config(workers, plugin).toBuilder().withSerDes(serDes).build();
        try {
            assertSame(
                    failure,
                    assertThrows(
                            IllegalStateException.class,
                            () -> DurableExecutor.execute(
                                    input(), null, get(String.class), (input, context) -> "done", config)));
            assertEquals(1, plugin.starts.size());
            assertEquals(1, plugin.ends.size());
            assertEquals(plugin.startThreads, plugin.endThreads);
            assertEquals(InvocationStatus.FAILED, plugin.ends.get(0).invocationStatus());
            assertSame(failure, plugin.ends.get(0).executionError());
            assertNull(plugin.ends.get(0).executionResult());
        } finally {
            stop(workers);
        }
    }

    @Test
    void retryableLargeResultCheckpointFailureEndsWithOriginalRetryError() throws Exception {
        var failure = new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorMessage("retry delivery").build(), true);
        var client = TestUtils.createMockClient();
        when(client.checkpoint(any(), any(), any())).thenThrow(failure);
        var plugin = new RecordingPlugin();
        var workers = Executors.newSingleThreadExecutor();
        var config = config(workers, plugin).toBuilder()
                .withDurableExecutionClient(client)
                .build();
        try {
            assertSame(
                    failure,
                    assertThrows(
                            UnrecoverableDurableExecutionException.class,
                            () -> DurableExecutor.execute(
                                    input(),
                                    null,
                                    get(String.class),
                                    (input, context) -> "x".repeat(7 * 1024 * 1024),
                                    config)));
            assertEquals(1, plugin.starts.size());
            assertEquals(1, plugin.ends.size());
            assertEquals(plugin.startThreads, plugin.endThreads);
            assertEquals(InvocationStatus.RETRYING, plugin.ends.get(0).invocationStatus());
            assertSame(failure, plugin.ends.get(0).executionError());
            assertNull(plugin.ends.get(0).executionResult());
        } finally {
            stop(workers);
        }
    }

    private static DurableConfig config(ExecutorService executor, DurableExecutionPlugin plugin) {
        return DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withExecutorService(executor)
                .withPlugins(plugin)
                .build();
    }

    private static ExecutorService executor(Consumer<Runnable> dispatch) {
        var executor = mock(ExecutorService.class);
        doAnswer(call -> {
                    dispatch.accept(call.getArgument(0));
                    return null;
                })
                .when(executor)
                .execute(any(Runnable.class));
        return executor;
    }

    private static void awaitTask(ExecutorService workers, Runnable task) {
        try {
            workers.submit(task).get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("latch not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void stop(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    private static DurableExecutionInput input() {
        var execution = Operation.builder()
                .id("execution")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.parse("2026-08-15T00:00:00Z"))
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/execution",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(execution).build());
    }

    private static class RecordingPlugin implements DurableExecutionPlugin {
        final ThreadLocal<String> local = new ThreadLocal<>();
        final List<InvocationInfo> starts = new ArrayList<>();
        final List<InvocationEndInfo> ends = new ArrayList<>();
        final List<Thread> startThreads = new ArrayList<>();
        final List<Thread> endThreads = new ArrayList<>();
        final List<String> endLocalValues = new ArrayList<>();
        final List<Map<String, String>> startMdc = new ArrayList<>();

        @Override
        public void onInvocationStart(InvocationInfo info) {
            starts.add(info);
            startThreads.add(Thread.currentThread());
            startMdc.add(MDC.getCopyOfContextMap());
            local.set("invocation");
            MDC.put("plugin", "invocation");
        }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) {
            ends.add(info);
            endThreads.add(Thread.currentThread());
            endLocalValues.add(local.get());
            local.remove();
            MDC.put("plugin", "ended");
        }
    }
}
