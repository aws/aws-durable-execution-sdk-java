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
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class IndependentWrappedOwnedFatalTest {
    @ParameterizedTest
    @CsvSource({"false, false, true", "true, false, true", "false, true, true", "true, true, true", "true, false, false"
    })
    void wrappedOwnedShutdownFailureRetainsIdentity(boolean executionWrapper, boolean acknowledged, boolean fatalCause)
            throws Exception {
        Throwable cause = fatalCause
                ? new InternalError("wrapped owned shutdown fatal")
                : new IllegalStateException("ordinary application failure");
        Throwable wrapped = executionWrapper ? new ExecutionException(cause) : new CompletionException(cause);
        var injectFailure = new AtomicBoolean(true);
        var shutdownCalls = new AtomicInteger();
        var users = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
            @Override
            public void shutdown() {
                shutdownCalls.incrementAndGet();
                super.shutdown();
                if (injectFailure.get()) ExceptionHelper.sneakyThrow(wrapped);
            }
        };
        var callers = Executors.newSingleThreadExecutor();
        var caller = new AtomicReference<Thread>();
        var callerInterrupted = new AtomicBoolean();
        var endInfo = new AtomicReference<InvocationEndInfo>();
        var endEntered = new CountDownLatch(1);
        var releaseEnd = new CountDownLatch(acknowledged ? 1 : 0);
        var client = TestUtils.createMockClient();
        var result = acknowledged ? "x".repeat(6 * 1024 * 1024 + 100) : "result";
        var config = DurableConfig.builder()
                .withInvocationExecutorFactory(() -> users)
                .withDurableExecutionClient(client)
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    @Override
                    public void onInvocationEnd(InvocationEndInfo info) {
                        endInfo.set(info);
                        endEntered.countDown();
                        try {
                            assertTrue(releaseEnd.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                })
                .build();
        try {
            var invocation = callers.submit(() -> {
                caller.set(Thread.currentThread());
                try {
                    return DurableExecutor.execute(
                            input(), runtime(10_000), TypeToken.get(String.class), (value, context) -> result, config);
                } finally {
                    callerInterrupted.set(Thread.interrupted());
                }
            });
            assertTrue(endEntered.await(5, TimeUnit.SECONDS));
            assertEquals(InvocationStatus.SUCCEEDED, endInfo.get().invocationStatus());
            assertNull(endInfo.get().executionError());
            if (acknowledged) caller.get().interrupt();
            assertSame(
                    fatalCause ? cause : wrapped,
                    assertThrows(ExecutionException.class, () -> invocation.get(5, TimeUnit.SECONDS))
                            .getCause());
            assertEquals(acknowledged, callerInterrupted.get());
            assertEquals(1, shutdownCalls.get());
            if (acknowledged)
                verify(client, times(1))
                        .checkpoint(
                                any(),
                                any(),
                                argThat(updates -> updates.size() == 1
                                        && updates.get(0).type() == OperationType.EXECUTION
                                        && updates.get(0).action() == OperationAction.SUCCEED
                                        && updates.get(0).payload().equals('"' + result + '"')));
        } finally {
            releaseEnd.countDown();
            injectFailure.set(false);
            users.shutdownNow();
            callers.shutdownNow();
            assertTrue(users.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(callers.awaitTermination(3, TimeUnit.SECONDS));
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
