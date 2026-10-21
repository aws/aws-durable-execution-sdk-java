// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.JacksonSerDes;

@Timeout(15)
class IndependentOwnedShutdownFatalTest {
    @SuppressWarnings("removal")
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializationFatalRemainsPrimaryWhenOwnedShutdownIntroducesAnotherFatal(boolean deathFirst) throws Exception {
        Error first = deathFirst ? new ThreadDeath() : new InternalError("first serializer fatal");
        Error second = deathFirst ? new InternalError("later owned shutdown fatal") : new ThreadDeath();
        var workerFailure = new AtomicReference<Throwable>();
        var escaped = new CountDownLatch(1);
        var ends = new AtomicInteger();
        var owner = new AtomicReference<Thread>();
        var users =
                new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), action -> {
                    var thread = new Thread(action, "independent-fatal-owner");
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        workerFailure.set(failure);
                        escaped.countDown();
                    });
                    return thread;
                }) {
                    @Override
                    public void shutdown() {
                        super.shutdown();
                        throw second;
                    }
                };
        var callers = Executors.newSingleThreadExecutor();
        var serializer = new JacksonSerDes() {
            @Override
            public String serialize(Object value) {
                if ("result".equals(value)) throw first;
                return super.serialize(value);
            }
        };
        try {
            var config = DurableConfig.builder()
                    .withInvocationExecutorFactory(() -> users)
                    .withDurableExecutionClient(TestUtils.createMockClient())
                    .withSerDes(serializer)
                    .withPlugins(ignoredInvocation -> new DurableExecutionPlugin() {
                        @Override
                        public void onInvocationStart(InvocationInfo info) {
                            owner.set(Thread.currentThread());
                        }

                        @Override
                        public void onInvocationEnd(InvocationEndInfo info) {
                            assertSame(owner.get(), Thread.currentThread());
                            assertEquals(InvocationStatus.RETRYING, info.invocationStatus());
                            assertSame(first, info.executionError());
                            ends.incrementAndGet();
                        }
                    })
                    .build();
            var invocation = callers.submit(() -> DurableExecutor.execute(
                    input(), runtime(10_000), TypeToken.get(String.class), (value, context) -> "result", config));
            assertSame(
                    first,
                    assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS))
                            .getCause());
            assertTrue(escaped.await(3, TimeUnit.SECONDS));
            assertSame(first, workerFailure.get());
            assertEquals(1, ends.get());
            assertTrue(Arrays.asList(first.getSuppressed()).contains(second));
        } finally {
            callers.shutdownNow();
            users.shutdownNow();
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(users.awaitTermination(3, TimeUnit.SECONDS));
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
