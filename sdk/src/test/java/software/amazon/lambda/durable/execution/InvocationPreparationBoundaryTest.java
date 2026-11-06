// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(20)
class InvocationPreparationBoundaryTest {
    @SuppressWarnings("removal")
    @ParameterizedTest
    @CsvSource({
        "false,true,false,false", "true,true,false,false",
        "false,true,true,false", "true,true,true,false",
        "false,false,false,false", "true,false,false,false",
        "false,false,true,false", "true,false,true,false",
        "false,true,false,true", "true,true,false,true"
    })
    void capturedInputFatalSurvivesAdmissionCutoff(boolean plugins, boolean expired, boolean death, boolean wrapped) {
        Error fatal = death ? new ThreadDeath() : new InternalError("input deserializer fatal");
        Throwable inputFailure = wrapped ? new CompletionException(fatal) : fatal;
        var reads = new AtomicInteger();
        var bodies = new AtomicInteger();
        var starts = new AtomicInteger();
        var ends = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        var executor = mock(ExecutorService.class);
        doAnswer(call -> {
                    ((Runnable) call.getArgument(0)).run();
                    return null;
                })
                .when(executor)
                .execute(any());
        var serializer = new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> type) {
                reads.incrementAndGet();
                if (expired) {
                    try {
                        assertFalse(new CountDownLatch(1).await(1600, TimeUnit.MILLISECONDS));
                    } catch (InterruptedException interrupted) {
                        throw new AssertionError(interrupted);
                    }
                }
                ExceptionHelper.sneakyThrow(inputFailure);
                return null;
            }
        };
        var client = TestUtils.createMockClient();
        var builder = DurableConfig.builder()
                .withExecutorService(executor)
                .withDurableExecutionClient(client)
                .withSerDes(serializer);
        if (plugins)
            builder.withPlugins(info -> new DurableExecutionPlugin() {
                public void onInvocationStart(InvocationInfo info) {
                    starts.incrementAndGet();
                    assertNull(info.executionInput());
                }

                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.incrementAndGet();
                    end.set(info);
                }
            });
        var observed = assertThrows(
                Throwable.class,
                () -> DurableExecutor.execute(
                        input(),
                        runtime(expired ? 2000 : 10_000),
                        TypeToken.get(String.class),
                        (value, context) -> {
                            bodies.incrementAndGet();
                            return value;
                        },
                        builder.build()));
        assertSame(fatal, observed, "A captured input JVM fatal must outrank a later admission rejection");
        assertEquals(1, reads.get());
        assertEquals(0, bodies.get());
        assertEquals(plugins && !expired ? 1 : 0, starts.get(), "Expired input cannot start new plugin work");
        assertEquals(starts.get(), ends.get());
        if (end.get() != null) {
            assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
            assertSame(fatal, end.get().executionError());
        }
        verify(client, never()).checkpoint(any(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void terminalEndReflectsCancellationDuringNormalSerialization(boolean handlerFails, boolean cancel)
            throws Exception {
        var serializationEntered = new CountDownLatch(1);
        var serializationInterrupted = new CountDownLatch(1);
        var releaseSerialization = new CountDownLatch(1);
        var endCount = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        var owner = new AtomicReference<Thread>();
        var caller = new AtomicReference<Thread>();
        var callerInterrupted = new AtomicBoolean();
        var applicationFailure = new IllegalStateException("handler failed");
        var workers = Executors.newSingleThreadExecutor();
        var callers = Executors.newSingleThreadExecutor();
        var serializer = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if ("result".equals(value) || value == applicationFailure) {
                    assertSame(owner.get(), Thread.currentThread());
                    serializationEntered.countDown();
                    awaitIgnoringInterruptions(releaseSerialization, serializationInterrupted);
                }
                return super.serialize(value);
            }
        };
        var client = TestUtils.createMockClient();
        var config = DurableConfig.builder()
                .withExecutorService(workers)
                .withDurableExecutionClient(client)
                .withSerDes(serializer)
                .withPlugins(info -> new DurableExecutionPlugin() {
                    public void onInvocationStart(InvocationInfo info) {
                        owner.set(Thread.currentThread());
                    }

                    public void onInvocationEnd(InvocationEndInfo info) {
                        assertSame(owner.get(), Thread.currentThread());
                        endCount.incrementAndGet();
                        end.set(info);
                    }
                })
                .build();
        try {
            var response = callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input(),
                            runtime(10_000),
                            TypeToken.get(String.class),
                            (value, context) -> {
                                if (handlerFails) throw applicationFailure;
                                return "result";
                            },
                            config);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            assertTrue(serializationEntered.await(5, TimeUnit.SECONDS));
            if (cancel) {
                caller.get().interrupt();
                assertTrue(serializationInterrupted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> response.get(100, TimeUnit.MILLISECONDS));
                assertEquals(0, endCount.get(), "End must await the actual serializer owner");
            }
            releaseSerialization.countDown();
            if (cancel) {
                var failure = assertInstanceOf(
                        UnrecoverableDurableExecutionException.class,
                        assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS))
                                .getCause());
                assertTrue(failure.isRetryable());
                assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
                assertSame(failure, end.get().executionError());
                assertNull(end.get().executionResult());
            } else {
                assertEquals(
                        handlerFails ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED,
                        response.get(5, TimeUnit.SECONDS).status());
                assertEquals(
                        handlerFails ? InvocationStatus.FAILED : InvocationStatus.SUCCEEDED,
                        end.get().invocationStatus());
                assertSame(handlerFails ? applicationFailure : null, end.get().executionError());
            }
            assertEquals(1, endCount.get());
            assertEquals(cancel, callerInterrupted.get());
            verify(client, never()).checkpoint(any(), any(), any());
        } finally {
            releaseSerialization.countDown();
            workers.shutdownNow();
            callers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void awaitIgnoringInterruptions(CountDownLatch release, CountDownLatch interrupted) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                assertTrue(release.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                return;
            } catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }

    private static Context runtime(int millis) {
        var context = mock(Context.class);
        when(context.getRemainingTimeInMillis()).thenReturn(millis);
        when(context.getAwsRequestId()).thenReturn("preparation-request");
        return context;
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
