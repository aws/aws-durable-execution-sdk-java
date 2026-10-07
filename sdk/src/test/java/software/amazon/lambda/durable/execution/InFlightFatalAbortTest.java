// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.CheckpointDurableExecutionResponse;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.util.ExceptionHelper;

class InFlightFatalAbortTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("removal")
    void activeBatchAndAlreadyWaitingShutdownObserveFatalWithoutBackendReturn(boolean threadDeath) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("active batch fatal");
        var batcher = new ApiRequestDelayedBatcher<String>(10, 100, String::length, values -> block(entered, release));
        var closing = Executors.newSingleThreadExecutor();
        var request = batcher.submit("START", Duration.ZERO);
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var shutdown = closing.submit(batcher::shutdown);
            assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
            batcher.abortPending(fatal);
            assertFatal(request, fatal);
            assertFatal(shutdown, fatal);
            assertEquals(1, release.getCount(), "fatal handling must not need the backend to return");
            assertFatal(batcher.submit("later", Duration.ZERO), fatal);
        } finally {
            release.countDown();
            closing.shutdown();
            assertTrue(closing.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("removal")
    void checkpointShutdownDoesNotWaitForBackendHeldPollerLockAfterFatal(boolean threadDeath) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Error fatal = threadDeath ? new ThreadDeath() : new InternalError("checkpoint fatal");
        var signal = new AtomicReference<Error>();
        var client = mock(DurableExecutionClient.class);
        when(client.checkpoint(any(), any(), any())).thenAnswer(call -> {
            block(entered, release);
            return CheckpointDurableExecutionResponse.builder()
                    .checkpointToken("next")
                    .build();
        });
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withCheckpointDelay(Duration.ZERO)
                .build();
        var manager = new CheckpointManager(config, "arn", "token", ops -> {}, () -> true, () -> {}, signal::get);
        var closing = Executors.newSingleThreadExecutor();
        var request = manager.checkpoint(OperationUpdate.builder()
                .id("step")
                .type(OperationType.STEP)
                .action(OperationAction.START)
                .build());
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var shutdown = closing.submit(manager::shutdown);
            assertThrows(TimeoutException.class, () -> shutdown.get(100, TimeUnit.MILLISECONDS));
            signal.set(fatal);
            manager.abortPending(fatal);
            assertFatal(request, fatal);
            assertFatal(shutdown, fatal);
            assertEquals(1, release.getCount());
        } finally {
            release.countDown();
            closing.shutdown();
            assertTrue(closing.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void assertFatal(Future<?> result, Error fatal) {
        var failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertSame(fatal, ExceptionHelper.unwrapAsyncFailure(failure));
    }

    private static void block(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("backend release timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("backend must not be interrupted", failure);
        }
    }
}
