// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.exception.ParallelBranchSkippedException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * Repros for the three review findings on skipped parallel branches: {@code anyOf} replay, the completion-snapshot
 * race, and nested skip exceptions classified as failures.
 */
class ParallelSkippedBranchReviewTest {

    private static final Duration DEADLINE = Duration.ofSeconds(20);

    @Test
    void anyOfKeepsTheRunningWinnerWhenTheOtherBranchIsSkippedOnReplay() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var handlerThread = Thread.currentThread();
            var parallel = context.parallel(
                    "first-successful",
                    ParallelConfig.builder()
                            .maxConcurrency(1)
                            .completionConfig(CompletionConfig.firstSuccessful())
                            .build());
            var winner = parallel.branch("winner", String.class, ctx -> {
                waitUntilParked(handlerThread);
                return "winner";
            });
            var skipped = parallel.branch("skipped", String.class, ctx -> "skipped");
            var first = String.valueOf(DurableFuture.anyOf(skipped, winner));
            context.wait("pause", Duration.ofSeconds(1));
            var second = String.valueOf(DurableFuture.anyOf(skipped, winner));
            return first + "," + second;
        });

        var pending = assertTimeoutPreemptively(DEADLINE, () -> runner.run("input"));
        assertEquals(ExecutionStatus.PENDING, pending.getStatus());

        runner.advanceTime();
        var replay = assertTimeoutPreemptively(DEADLINE, () -> runner.run("input"));
        assertEquals(ExecutionStatus.SUCCEEDED, replay.getStatus());
        assertEquals("winner,winner", replay.getResult(String.class));
    }

    @Test
    void branchGetKeepsTheSameOutcomeWhenItFinishesDuringResultSerialization() {
        var branchRunning = new CountDownLatch(1);
        var allowBranch = new CountDownLatch(1);
        var serDes = new ParallelResultSerializationGate(allowBranch);
        var config = DurableConfig.builder().withSerDes(serDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var parallel = context.parallel(
                            "first-successful",
                            ParallelConfig.builder()
                                    .completionConfig(CompletionConfig.firstSuccessful())
                                    .build());
                    var slow = parallel.branch("branch-b", String.class, ctx -> {
                        branchRunning.countDown();
                        await(allowBranch, "release branch-b");
                        return "B";
                    });
                    parallel.branch("branch-a", String.class, ctx -> {
                        await(branchRunning, "branch-b to start");
                        return "A";
                    });
                    var result = parallel.get();
                    return "after-parallel|" + observe(slow) + "|" + result.statuses();
                },
                config);

        try {
            var execution = assertTimeoutPreemptively(DEADLINE, () -> runner.runUntilComplete("input"));
            assertEquals(ExecutionStatus.SUCCEEDED, execution.getStatus());
            assertEquals("after-parallel|SKIPPED:branch-b|[SKIPPED, SUCCEEDED]", execution.getResult(String.class));
        } finally {
            allowBranch.countDown();
        }
    }

    @Test
    void skippedBranchStaysSkippedOnReplayWhenParallelResultIsAlreadyCheckpointed() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var parallel = context.parallel(
                    "first-successful",
                    ParallelConfig.builder()
                            .completionConfig(CompletionConfig.firstSuccessful())
                            .build());
            parallel.branch("branch-a", String.class, ctx -> "A");
            var skipped = parallel.branch("branch-b", String.class, ctx -> "B");
            var result = parallel.get();
            context.step("record", String.class, stepCtx -> observe(skipped) + "|" + result.statuses());
            return "done";
        });

        var first = assertTimeoutPreemptively(DEADLINE, () -> runner.runUntilComplete("input"));
        assertEquals(ExecutionStatus.SUCCEEDED, first.getStatus());

        var replay = assertTimeoutPreemptively(DEADLINE, () -> runner.run("input"));
        assertEquals(ExecutionStatus.SUCCEEDED, replay.getStatus());
        assertEquals("done", replay.getResult(String.class));
    }

    @Test
    void nestedSkipExceptionStaysAFailedOuterBranch() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var outer = context.parallel("outer", ParallelConfig.builder().build());
            var outerBranch = outer.branch("outer-branch", String.class, ctx -> {
                var inner = ctx.parallel(
                        "inner",
                        ParallelConfig.builder()
                                .maxConcurrency(1)
                                .completionConfig(CompletionConfig.firstSuccessful())
                                .build());
                inner.branch("inner-winner", String.class, innerCtx -> "ok");
                var skipped = inner.branch("inner-skipped", String.class, innerCtx -> "no");
                inner.get();
                skipped.get();
                return "unreachable";
            });

            var result = outer.get();
            assertEquals(List.of(ParallelResult.Status.FAILED), result.statuses());
            assertEquals(1, result.failed());
            assertEquals(0, result.skipped());
            var thrown = assertThrows(ParallelBranchSkippedException.class, outerBranch::get);
            assertEquals("inner-skipped", thrown.getBranchName());
            return "done";
        });

        var first = assertTimeoutPreemptively(DEADLINE, () -> runner.runUntilComplete("input"));
        assertEquals(ExecutionStatus.SUCCEEDED, first.getStatus());
        assertEquals("done", first.getResult(String.class));
        var checkpoint = runner.getOperation("outer-branch");
        assertEquals(OperationStatus.FAILED, checkpoint.getStatus());
        assertTrue(checkpoint.getContextDetails().error().errorMessage().contains("inner-skipped"));

        var replay = assertTimeoutPreemptively(DEADLINE, () -> runner.run("input"));
        assertEquals(ExecutionStatus.SUCCEEDED, replay.getStatus());
        assertEquals("done", replay.getResult(String.class));
    }

    private static void waitUntilParked(Thread handlerThread) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (handlerThread.getState() != Thread.State.WAITING
                && handlerThread.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("timed out waiting for anyOf to park");
            }
            Thread.onSpinWait();
        }
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for " + description);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for " + description, e);
        }
    }

    private static String observe(DurableFuture<?> future) {
        try {
            return String.valueOf(future.get());
        } catch (ParallelBranchSkippedException e) {
            return "SKIPPED:" + e.getBranchName();
        }
    }

    /** Blocks inside {@link ParallelResult} serialization so a branch can finish in that window. */
    private static final class ParallelResultSerializationGate implements SerDes {
        private final JacksonSerDes delegate = new JacksonSerDes();
        private final CountDownLatch allowBranch;
        private final AtomicBoolean gated = new AtomicBoolean();

        private ParallelResultSerializationGate(CountDownLatch allowBranch) {
            this.allowBranch = allowBranch;
        }

        @Override
        public String serialize(Object value) {
            if (value instanceof ParallelResult && gated.compareAndSet(false, true)) {
                allowBranch.countDown();
            }
            return delegate.serialize(value);
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            return delegate.deserialize(data, typeToken);
        }
    }
}
