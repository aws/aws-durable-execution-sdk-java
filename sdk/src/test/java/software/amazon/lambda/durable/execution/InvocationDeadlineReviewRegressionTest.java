// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static software.amazon.lambda.durable.TypeToken.get;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.serde.JacksonSerDes;

@Timeout(20)
class InvocationDeadlineReviewRegressionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void caughtDeadlineAdmissionCannotPublishSuccessfulFallback(boolean plugins) {
        var caught = new AtomicReference<UnrecoverableDurableExecutionException>();
        var bodyEntered = new AtomicBoolean();
        var effects = new AtomicInteger();
        var fallbackSerializations = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        var config = config(
                inlineExecutor(),
                new JacksonSerDes() {
                    @Override
                    public String serialize(Object value) {
                        if ("fallback".equals(value)) fallbackSerializations.incrementAndGet();
                        return super.serialize(value);
                    }
                },
                plugins,
                end);
        var failure = assertThrows(
                UnrecoverableDurableExecutionException.class,
                () -> DurableExecutor.execute(
                        input(),
                        runtime(2_000),
                        get(String.class),
                        (value, context) -> {
                            bodyEntered.set(true);
                            try {
                                TimeUnit.MILLISECONDS.sleep(1_600);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                            try {
                                context.step("too-late", String.class, step -> {
                                    effects.incrementAndGet();
                                    return "side-effect";
                                });
                            } catch (UnrecoverableDurableExecutionException interrupted) {
                                caught.set(interrupted);
                            }
                            return "fallback";
                        },
                        config));
        assertTrue(bodyEntered.get(), "The handler must reach the caught-admission path");
        assertSame(caught.get(), failure, "Swallowing a deadline rejection must not erase its selected failure");
        assertTrue(failure.isRetryable());
        assertEquals(0, effects.get());
        assertEquals(0, fallbackSerializations.get(), "An expired invocation must not prepare a fallback success");
        if (plugins) {
            assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
            assertSame(failure, end.get().executionError());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inlineSuccessBeforeTheCutoffIsStillAllowed(boolean plugins) {
        var end = new AtomicReference<InvocationEndInfo>();
        var config = config(inlineExecutor(), new JacksonSerDes(), plugins, end);
        var output = DurableExecutor.execute(
                input(), runtime(10_000), get(String.class), (value, context) -> "result", config);
        assertEquals(ExecutionStatus.SUCCEEDED, output.status());
        assertEquals("\"result\"", output.result());
        if (plugins) assertEquals(InvocationStatus.SUCCEEDED, end.get().invocationStatus());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anAlreadySelectedSuspensionSurvivesLaterCaughtAdmission(boolean plugins) {
        var end = new AtomicReference<InvocationEndInfo>();
        var effects = new AtomicInteger();
        var config = config(inlineExecutor(), new JacksonSerDes(), plugins, end);
        var output = DurableExecutor.execute(
                input(),
                runtime(2_000),
                get(String.class),
                (value, context) -> {
                    try {
                        context.wait("pause", Duration.ofSeconds(5));
                        throw new AssertionError("The wait should select suspension");
                    } catch (SuspendExecutionException suspended) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(1_600);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        }
                        try {
                            context.step("too-late", String.class, step -> {
                                effects.incrementAndGet();
                                return "side-effect";
                            });
                        } catch (RuntimeException stopped) {
                            // A rejected operation must not replace the suspension already selected by the durable
                            // wait.
                        }
                        return "fallback";
                    }
                },
                config);
        assertEquals(ExecutionStatus.PENDING, output.status());
        assertEquals(0, effects.get());
        if (plugins) assertEquals(InvocationStatus.PENDING, end.get().invocationStatus());
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    void cancellationDuringSerializationRetainsFatalPriority(boolean plugins, boolean fatal) throws Exception {
        var serializationEntered = new CountDownLatch(1);
        var serializerInterrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var uncaught = new CountDownLatch(1);
        var uncaughtFailure = new AtomicReference<Throwable>();
        var runtimeThread = new AtomicReference<Thread>();
        var end = new AtomicReference<InvocationEndInfo>();
        Throwable serializationFailure = fatal
                ? new InternalError("serializer fatal")
                : new IllegalStateException("ordinary serializer failure");
        var workers = Executors.newSingleThreadExecutor(action -> {
            var worker = new Thread(action, "serialization-owner");
            worker.setUncaughtExceptionHandler((thread, error) -> {
                uncaughtFailure.set(error);
                uncaught.countDown();
            });
            return worker;
        });
        var callers = Executors.newSingleThreadExecutor();
        var serializer = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if ("result".equals(value)) {
                    serializationEntered.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        serializerInterrupted.countDown();
                        Thread.currentThread().interrupt();
                        if (serializationFailure instanceof Error error) throw error;
                        throw (RuntimeException) serializationFailure;
                    }
                }
                return super.serialize(value);
            }
        };
        try {
            var config = config(workers, serializer, plugins, end);
            var invocation = callers.submit(() -> {
                runtimeThread.set(Thread.currentThread());
                return DurableExecutor.execute(
                        input(), runtime(10_000), get(String.class), (value, context) -> "result", config);
            });
            assertTrue(serializationEntered.await(5, TimeUnit.SECONDS));
            runtimeThread.get().interrupt();
            assertTrue(serializerInterrupted.await(5, TimeUnit.SECONDS), "Cleanup must physically interrupt the owner");
            var actual = assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS))
                    .getCause();
            if (fatal) {
                assertSame(serializationFailure, actual, "An available JVM fatal must beat lifecycle cancellation");
                assertTrue(uncaught.await(5, TimeUnit.SECONDS), "The actual worker must not absorb a JVM fatal");
                assertSame(serializationFailure, uncaughtFailure.get());
            } else {
                assertInstanceOf(UnrecoverableDurableExecutionException.class, actual);
                assertNotSame(
                        serializationFailure, actual, "An ordinary late error must not replace caller interruption");
            }
            if (plugins) {
                assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
                assertSame(serializationFailure, end.get().executionError());
            }
        } finally {
            release.countDown();
            callers.shutdownNow();
            workers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static DurableConfig config(
            ExecutorService executor,
            JacksonSerDes serializer,
            boolean plugins,
            AtomicReference<InvocationEndInfo> end) {
        var builder = DurableConfig.builder()
                .withExecutorService(executor)
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withCheckpointDelay(Duration.ZERO)
                .withSerDes(serializer);
        if (plugins)
            builder.withPlugins(new DurableExecutionPlugin() {
                @Override
                public void onInvocationEnd(InvocationEndInfo info) {
                    end.set(info);
                }
            });
        return builder.build();
    }

    private static ExecutorService inlineExecutor() {
        var executor = mock(ExecutorService.class);
        doAnswer(call -> {
                    ((Runnable) call.getArgument(0)).run();
                    return null;
                })
                .when(executor)
                .execute(any());
        return executor;
    }

    private static Context runtime(int millis) {
        var runtime = mock(Context.class);
        when(runtime.getRemainingTimeInMillis()).thenReturn(millis);
        when(runtime.getAwsRequestId()).thenReturn("runtime-request");
        return runtime;
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/review/exec",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("exec")
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
