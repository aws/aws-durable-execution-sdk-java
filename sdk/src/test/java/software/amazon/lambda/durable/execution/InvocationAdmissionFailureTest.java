// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Instant;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;

@Timeout(15)
class InvocationAdmissionFailureTest {
    @ParameterizedTest
    @CsvSource({"0,false", "0,true", "1,false", "1,true", "499,false", "499,true", "500,false", "500,true"})
    void expiredRootAdmissionRetainsRetryableFailureWithoutDispatch(int remainingMillis, boolean plugins)
            throws Exception {
        try (var fixture = new Fixture(plugins, null)) {
            var failure =
                    assertThrows(UnrecoverableDurableExecutionException.class, () -> fixture.invoke(remainingMillis));
            assertTrue(failure.isRetryable());
            assertEquals(
                    "software.amazon.lambda.durable.execution.InvocationLifecycleException",
                    failure.getErrorObject().errorType());
            assertEquals("Invocation deadline reached before user work", failure.getMessage());
            assertInstanceOf(TimeoutException.class, failure.getCause());
            fixture.assertNoUserWork();
            assertEquals(0, fixture.dispatches.get());
            assertFalse(fixture.users.isShutdown(), "Shared executors remain caller-owned after rejected admission");
            verifyNoInteractions(fixture.client);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void usableDeadlineStillRunsTheHandler(boolean plugins) throws Exception {
        try (var fixture = new Fixture(plugins, null)) {
            var response = fixture.invoke(10_000);
            assertEquals(ExecutionStatus.SUCCEEDED, response.status());
            assertEquals("\"input\"", response.result());
            assertEquals(1, fixture.dispatches.get());
            assertEquals(1, fixture.bodies.get());
            assertEquals(plugins ? 1 : 0, fixture.starts.get());
            assertEquals(plugins ? 1 : 0, fixture.ends.get());
            assertFalse(fixture.users.isShutdown());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void synchronousExecutorRejectionRetainsItsOriginalIdentity(boolean plugins) throws Exception {
        var rejection = new RejectedExecutionException("executor rejected the root");
        try (var fixture = new Fixture(plugins, rejection)) {
            assertSame(rejection, assertThrows(RejectedExecutionException.class, () -> fixture.invoke(10_000)));
            fixture.assertNoUserWork();
            assertEquals(1, fixture.dispatches.get());
            assertFalse(fixture.users.isShutdown());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final AtomicInteger dispatches = new AtomicInteger();
        private final AtomicInteger bodies = new AtomicInteger();
        private final AtomicInteger factories = new AtomicInteger();
        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger ends = new AtomicInteger();
        private final DurableExecutionClient client = mock(DurableExecutionClient.class);
        private final ThreadPoolExecutor users;
        private final DurableConfig config;

        private Fixture(boolean plugins, RejectedExecutionException rejection) {
            users = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()) {
                @Override
                public void execute(Runnable command) {
                    dispatches.incrementAndGet();
                    if (rejection != null) throw rejection;
                    super.execute(command);
                }
            };
            var builder = DurableConfig.builder().withExecutorService(users).withDurableExecutionClient(client);
            if (plugins)
                builder.withPlugins(ignored -> {
                    factories.incrementAndGet();
                    return new DurableExecutionPlugin() {
                        @Override
                        public void onInvocationStart(InvocationInfo info) {
                            starts.incrementAndGet();
                        }

                        @Override
                        public void onInvocationEnd(InvocationEndInfo info) {
                            ends.incrementAndGet();
                        }
                    };
                });
            config = builder.build();
        }

        private DurableExecutionOutput invoke(int remainingMillis) {
            var runtime = mock(Context.class);
            when(runtime.getRemainingTimeInMillis()).thenReturn(remainingMillis);
            when(runtime.getAwsRequestId()).thenReturn("admission-request");
            return DurableExecutor.execute(
                    input(),
                    runtime,
                    TypeToken.get(String.class),
                    (value, context) -> {
                        bodies.incrementAndGet();
                        return value;
                    },
                    config);
        }

        private void assertNoUserWork() {
            assertEquals(0, bodies.get());
            assertEquals(0, factories.get());
            assertEquals(0, starts.get());
            assertEquals(0, ends.get());
        }

        @Override
        public void close() throws InterruptedException {
            users.shutdownNow();
            assertTrue(users.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:execution/admission",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("admission")
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
