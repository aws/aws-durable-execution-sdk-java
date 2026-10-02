// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.TypeToken.get;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

class FatalInvocationBoundaryTest {
    @SuppressWarnings("removal")
    static Stream<Arguments> fatalCases() {
        return Stream.of("factory", "hook", "handler", "wrapped-handler")
                .flatMap(stage -> Stream.of(new OutOfMemoryError("simulated VM failure"), new ThreadDeath())
                        .map(error -> Arguments.of(stage, error)));
    }

    @ParameterizedTest
    @MethodSource("fatalCases")
    void fatalCauseEscapesCallerAndWorkerAfterFinalization(String stage, Error fatal) throws Exception {
        var uncaught = new AtomicReference<Throwable>();
        var workerFailure = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "fatal-invocation-test");
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((failed, error) -> {
                uncaught.set(error);
                workerFailure.countDown();
            });
            return thread;
        });
        try {
            assertFatalInvocation(stage, fatal, executor);
            assertTrue(workerFailure.await(5, TimeUnit.SECONDS), "Fatal error must escape the async runnable");
            assertSame(fatal, uncaught.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("removal")
    void directExecutorFinalizesBeforeRethrowingFatalOnCaller() {
        assertFatalInvocation("factory", new ThreadDeath(), new DirectExecutor());
    }

    @Test
    void incompatibleOptionalApiDoesNotChangeHandlerResult() {
        var end = new AtomicReference<InvocationEndInfo>();
        DurableExecutionPluginFactory observer = info -> new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                end.set(info);
            }
        };
        DurableExecutionPluginFactory incompatible = info -> {
            throw new NoSuchMethodError("boolean io.opentelemetry.api.GlobalOpenTelemetry.isSet()");
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withPlugins(observer, incompatible)
                .build();
        var output = DurableExecutor.execute(input(), null, get(String.class), (value, context) -> value, config);
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("\"input\"", output.result());
        assertEquals(InvocationStatus.SUCCEEDED, end.get().invocationStatus());
    }

    private static void assertFatalInvocation(String stage, Error fatal, ExecutorService executor) {
        var end = new AtomicReference<InvocationEndInfo>();
        var called = new AtomicBoolean();
        DurableExecutionPluginFactory recorder = info -> new DurableExecutionPlugin() {
            @Override
            public void onInvocationEnd(InvocationEndInfo info) {
                assertNull(end.getAndSet(info), "End hooks must fire once");
            }
        };
        var config = DurableConfig.builder()
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withExecutorService(executor)
                .withPlugins(recorder, failingPlugin(stage, fatal))
                .build();
        assertSame(
                fatal,
                assertThrows(
                        Error.class,
                        () -> DurableExecutor.execute(
                                input(),
                                null,
                                get(String.class),
                                (value, context) -> {
                                    called.set(true);
                                    if (stage.equals("wrapped-handler")) throw new CompletionException(fatal);
                                    if (stage.equals("handler")) throw fatal;
                                    return value;
                                },
                                config)));
        assertEquals(stage.endsWith("handler"), called.get());
        assertNotNull(end.get());
        assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
        assertSame(fatal, end.get().executionError());
    }

    private static DurableExecutionPluginFactory failingPlugin(String stage, Error fatal) {
        return info -> {
            if (stage.equals("factory")) throw fatal;
            return new DurableExecutionPlugin() {
                @Override
                public void onInvocationStart(InvocationInfo info) {
                    if (stage.equals("hook")) throw fatal;
                }
            };
        };
    }

    private static DurableExecutionInput input() {
        var operation = Operation.builder()
                .id("id")
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.parse("2026-10-02T00:00:00Z"))
                .executionDetails(
                        ExecutionDetails.builder().inputPayload("\"input\"").build())
                .build();
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/id",
                "token",
                CheckpointUpdatedExecutionState.builder().operations(operation).build());
    }

    private static final class DirectExecutor extends AbstractExecutorService {
        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void shutdown() {}

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
