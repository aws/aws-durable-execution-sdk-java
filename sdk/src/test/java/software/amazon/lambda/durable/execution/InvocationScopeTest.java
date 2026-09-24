// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InvocationScopeTest {
    private static final String EXECUTION_ARN =
            "arn:aws:lambda:us-east-1:123456789012:function:test/durable-execution/test/execution";

    @Test
    void capturesRequestIdAndInvocationDeadline() {
        var context = mock(Context.class);
        when(context.getAwsRequestId()).thenReturn("request-id");
        when(context.getRemainingTimeInMillis()).thenReturn(2_000);

        var scope = new InvocationScope(context, EXECUTION_ARN);

        assertEquals("request-id", scope.invocationId());
        var remaining = scope.remainingTime().orElseThrow();
        assertTrue(remaining.compareTo(Duration.ZERO) > 0);
        assertTrue(remaining.compareTo(Duration.ofSeconds(2)) <= 0);
    }

    @Test
    void createsDistinctLocalIdsWithoutInventingADeadline() {
        var first = new InvocationScope(null, EXECUTION_ARN);
        var second = new InvocationScope(null, EXECUTION_ARN);

        assertNotEquals(first.invocationId(), second.invocationId());
        assertTrue(first.remainingTime().isEmpty());
    }

    @Test
    void registersTaskBeforeQueuedExecutionAndTracksItsExit() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var blockerEntered = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        executor.submit(() -> {
            blockerEntered.countDown();
            await(releaseBlocker);
        });
        assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));

        try {
            var scope = new InvocationScope(null, EXECUTION_ARN);
            var completion = scope.submit(InvocationTask.Kind.ROOT, null, executor, () -> "result");
            var task = scope.tasks().get(0);

            assertEquals(InvocationTask.State.REGISTERED, task.state());
            assertNotNull(task.execution());
            assertFalse(completion.isDone());
            assertFalse(task.exit().isDone());

            releaseBlocker.countDown();
            assertEquals("result", completion.get(5, TimeUnit.SECONDS));
            task.exit().get(5, TimeUnit.SECONDS);
            assertEquals(InvocationTask.State.EXITED, task.state());
            assertTrue(scope.tasks().isEmpty());
        } finally {
            releaseBlocker.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void taskCancellationSeparatesLogicalCompletionFromActualExit() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var scope = new InvocationScope(null, EXECUTION_ARN);
            var completion = scope.submit(InvocationTask.Kind.STEP, "step", executor, () -> {
                entered.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException expected) {
                        interrupted.countDown();
                    }
                }
                return "ignored";
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var task = scope.tasks().get(0);

            assertTrue(task.cancel(true));
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(completion.isCancelled());
            assertFalse(task.exit().isDone());

            release.countDown();
            task.exit().get(5, TimeUnit.SECONDS);
            assertEquals(InvocationTask.State.EXITED, task.state());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationRequestedBeforeExecutorHandleIsBoundIsNotLost() throws Exception {
        var exits = new AtomicInteger();
        var task = new InvocationTask<String>(1, InvocationTask.Kind.ROOT, null, exits::incrementAndGet);
        var execution = new FutureTask<Void>(() -> null);

        assertTrue(task.cancel(true));
        assertTrue(task.completion().isCancelled());
        assertFalse(task.exit().isDone());

        task.bindExecution(execution);

        assertTrue(execution.isCancelled());
        task.exit().get(5, TimeUnit.SECONDS);
        assertEquals(1, exits.get());
    }

    @Test
    void failedSubmissionIsRemovedFromScope() {
        var executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        var scope = new InvocationScope(null, EXECUTION_ARN);

        assertThrows(
                RejectedExecutionException.class,
                () -> scope.submit(InvocationTask.Kind.ROOT, null, executor, () -> "result"));
        assertTrue(scope.tasks().isEmpty());
    }

    @Test
    void drainingRejectsNewWorkButAllowsExistingCheckpointCleanup() {
        var scope = new InvocationScope(null, EXECUTION_ARN);
        var executor = Executors.newSingleThreadExecutor();
        scope.beginDraining();

        try {
            assertEquals(InvocationScope.State.DRAINING, scope.state());
            assertThrows(RejectedExecutionException.class, () -> scope.admitOperation(() -> {}));
            assertThrows(
                    RejectedExecutionException.class,
                    () -> scope.submit(InvocationTask.Kind.ROOT, null, executor, () -> "result"));
            assertEquals("checkpoint", scope.admitCheckpoint(() -> "checkpoint"));

            scope.close();
            assertEquals(InvocationScope.State.CLOSED, scope.state());
            assertThrows(RejectedExecutionException.class, () -> scope.admitCheckpoint(() -> "checkpoint"));
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test synchronization timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
