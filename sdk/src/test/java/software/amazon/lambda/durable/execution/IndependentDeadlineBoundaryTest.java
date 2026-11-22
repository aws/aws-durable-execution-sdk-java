// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
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
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class IndependentDeadlineBoundaryTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void cancelledQueuedRootCannotHangOrRunAfterCallerReturns(boolean plugin, boolean interrupted) throws Exception {
        var occupied = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var users = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        var callers = Executors.newSingleThreadExecutor();
        var caller = new AtomicReference<Thread>();
        var interruptRestored = new AtomicBoolean();
        var bodies = new AtomicInteger();
        var starts = new AtomicInteger();
        var ends = new AtomicInteger();
        var client = TestUtils.createMockClient();
        var builder = DurableConfig.builder().withExecutorService(users).withDurableExecutionClient(client);
        if (plugin)
            builder.withPlugins(ignoredInvocation -> new DurableExecutionPlugin() {
                @Override
                public void onInvocationStart(InvocationInfo info) {
                    starts.incrementAndGet();
                }

                @Override
                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.incrementAndGet();
                }
            });
        try {
            users.execute(() -> {
                occupied.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(occupied.await(3, TimeUnit.SECONDS));
            var config = builder.build();
            var result = callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input(),
                            runtime(interrupted ? 10_000 : 1_500),
                            TypeToken.get(String.class),
                            (value, context) -> {
                                bodies.incrementAndGet();
                                return "unexpected";
                            },
                            config);
                } finally {
                    interruptRestored.set(Thread.interrupted());
                }
            });
            var limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (users.getQueue().isEmpty() && System.nanoTime() < limit) Thread.yield();
            assertEquals(1, users.getQueue().size(), "Root must actually be queued before cancellation");
            if (interrupted) caller.get().interrupt();
            var cause = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS))
                    .getCause();
            var failure = assertInstanceOf(UnrecoverableDurableExecutionException.class, cause);
            assertTrue(failure.isRetryable());
            assertEquals(interrupted, interruptRestored.get());
            release.countDown();
            users.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertEquals(0, bodies.get());
            assertEquals(0, starts.get());
            assertEquals(0, ends.get());
            verify(client, never()).checkpoint(any(), any(), any());
        } finally {
            release.countDown();
            callers.shutdownNow();
            users.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(users.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void caughtLateInvokeCheckpointCannotPublishFallback(boolean plugin) {
        var caught = new AtomicReference<Throwable>();
        var serializations = new AtomicInteger();
        var fallbackSerializations = new AtomicInteger();
        var ends = new AtomicReference<InvocationEndInfo>();
        var users = mock(ExecutorService.class);
        doAnswer(call -> {
                    ((Runnable) call.getArgument(0)).run();
                    return null;
                })
                .when(users)
                .execute(any());
        var client = TestUtils.createMockClient();
        var builder = DurableConfig.builder()
                .withExecutorService(users)
                .withDurableExecutionClient(client)
                .withSerDes(new JacksonSerDes() {
                    @Override
                    public String serialize(Object value) {
                        if ("fallback".equals(value)) fallbackSerializations.incrementAndGet();
                        return super.serialize(value);
                    }
                });
        if (plugin)
            builder.withPlugins(ignoredInvocation -> new DurableExecutionPlugin() {
                @Override
                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.set(info);
                }
            });
        var payloadSerdes = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                serializations.incrementAndGet();
                try {
                    Thread.sleep(2_100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.serialize(value);
            }
        };
        var config = builder.build();
        var returned = new AtomicReference<Object>();
        var failure = assertThrows(
                UnrecoverableDurableExecutionException.class,
                () -> returned.set(DurableExecutor.execute(
                        input(),
                        runtime(2_000),
                        TypeToken.get(String.class),
                        (value, context) -> {
                            try {
                                context.invoke(
                                        "late-invoke",
                                        "callee:1",
                                        "payload",
                                        String.class,
                                        InvokeConfig.builder()
                                                .payloadSerDes(payloadSerdes)
                                                .build());
                            } catch (Throwable error) {
                                caught.set(error);
                            }
                            return "fallback";
                        },
                        config)),
                () -> "Observed output=" + returned.get() + "; caught=" + caught.get());
        assertEquals(1, serializations.get());
        assertNotNull(caught.get(), "The public handler must actually catch the late operation rejection");
        assertSame(failure, ExceptionHelper.unwrapCompletableFuture(caught.get()));
        assertEquals(0, fallbackSerializations.get(), "A rejected checkpoint must not prepare a fallback success");
        assertTrue(failure.isRetryable());
        verify(client, never()).checkpoint(any(), any(), any());
        if (plugin) {
            assertEquals(InvocationStatus.RETRYING, ends.get().invocationStatus());
            assertSame(failure, ends.get().executionError());
        }
    }

    private static Context runtime(int millis) {
        var context = mock(Context.class);
        when(context.getRemainingTimeInMillis()).thenReturn(millis);
        when(context.getAwsRequestId()).thenReturn("independent-request");
        return context;
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/independent/exec",
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
