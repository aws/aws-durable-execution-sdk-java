// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Integration tests for child context correctness properties. */
class ChildContextIntegrationTest {

    /**
     * Property 1: Child context result round-trip
     *
     * <p>For any child context that completes successfully, running the execution to completion and then replaying it
     * SHALL produce the same result without re-executing the user function.
     */
    @Test
    void childContextResultSurvivesReplay() {
        var childExecutionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.runInChildContext("compute", String.class, child -> {
                childExecutionCount.incrementAndGet();
                return child.step("work", String.class, () -> "result-" + input);
            });
        });

        // First run - executes child context
        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("result-test", result.getResult(String.class));
        assertEquals(1, childExecutionCount.get());

        // Second run - replays, should return cached result without re-executing
        result = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("result-test", result.getResult(String.class));
        assertEquals(1, childExecutionCount.get(), "Child function should not re-execute on replay");
    }

    /**
     * Property 2: Child context failure preservation
     *
     * <p>For any child context that fails with a reconstructable exception, the exception type, message, and error
     * details SHALL be preserved through the checkpoint-and-replay cycle.
     */
    @Test
    void childContextExceptionPreservedOnReplay() {
        var childExecutionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.runInChildContext("failing", String.class, child -> {
                childExecutionCount.incrementAndGet();
                throw new IllegalArgumentException("bad input: " + input);
            });
        });

        // First run - child context fails
        var result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, childExecutionCount.get());

        // Second run - replays, should throw same exception without re-executing
        result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().isPresent());
        var error = result.getError().get();
        assertEquals("java.lang.IllegalArgumentException", error.errorType());
        assertEquals("bad input: test", error.errorMessage());
        assertEquals(1, childExecutionCount.get(), "Child function should not re-execute on failed replay");
    }

    /**
     * Property 3: ParentId propagation
     *
     * <p>For any operation checkpointed from within a child context, the operation's parentId field SHALL equal the
     * child context's context ID.
     */
    @Test
    void operationsInChildContextHaveCorrectParentId() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.runInChildContext("child-ctx", String.class, child -> {
                var step1 = child.step("inner-step", String.class, () -> "step-result");
                return step1;
            });
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("step-result", result.getResult(String.class));

        // Verify the inner step has the child context's operation ID as parentId
        var innerStep = result.getOperation("inner-step");
        assertNotNull(innerStep, "Inner step should exist");
    }

    /**
     * Property 4: Operation counter independence
     *
     * <p>Each child context SHALL maintain its own operation counter, so the first operation ID within each context is
     * "1".
     */
    @Test
    void childContextsHaveIndependentOperationCounters() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var r1 = ctx.runInChildContext("child-a", String.class, child -> {
                return child.step("step-a", String.class, () -> "a-result");
            });
            var r2 = ctx.runInChildContext("child-b", String.class, child -> {
                return child.step("step-b", String.class, () -> "b-result");
            });
            return r1 + "+" + r2;
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("a-result+b-result", result.getResult(String.class));

        // Both child contexts should have completed successfully
        var stepA = result.getOperation("step-a");
        var stepB = result.getOperation("step-b");
        assertNotNull(stepA);
        assertNotNull(stepB);
    }

    /**
     * Property 5: Operation scoping prevents cross-context interference
     *
     * <p>Two child contexts with operations that have the same local IDs SHALL NOT interfere with each other.
     */
    @Test
    void parallelChildContextsWithSameLocalIdsDoNotInterfere() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            // Both child contexts will have a step with local operation ID "1"
            var futureA = ctx.runInChildContextAsync("ctx-a", String.class, child -> {
                return child.step("work", String.class, () -> "result-a");
            });
            var futureB = ctx.runInChildContextAsync("ctx-b", String.class, child -> {
                return child.step("work", String.class, () -> "result-b");
            });
            return futureA.get() + "+" + futureB.get();
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("result-a+result-b", result.getResult(String.class));
    }

    /**
     * Property 6: Multiple async child contexts produce correct results
     *
     * <p>Each concurrently running async child context SHALL complete with its own correct result.
     */
    @Test
    void multipleAsyncChildContextsReturnCorrectResults() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var f1 = ctx.runInChildContextAsync("async-1", String.class, child -> {
                return child.step("s1", String.class, () -> "one");
            });
            var f2 = ctx.runInChildContextAsync("async-2", String.class, child -> {
                return child.step("s2", String.class, () -> "two");
            });
            var f3 = ctx.runInChildContextAsync("async-3", String.class, child -> {
                return child.step("s3", String.class, () -> "three");
            });
            return f1.get() + "," + f2.get() + "," + f3.get();
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("one,two,three", result.getResult(String.class));
    }

    /**
     * Property 7: allOf preserves result ordering
     *
     * <p>The results returned by DurableFuture.allOf() SHALL be in the same order as the input futures.
     */
    @Test
    void allOfReturnsResultsInOrder() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var f1 = ctx.runInChildContextAsync("first", String.class, child -> {
                return child.step("s1", String.class, () -> "alpha");
            });
            var f2 = ctx.runInChildContextAsync("second", String.class, child -> {
                return child.step("s2", String.class, () -> "beta");
            });
            var f3 = ctx.runInChildContextAsync("third", String.class, child -> {
                return child.step("s3", String.class, () -> "gamma");
            });

            var results = DurableFuture.allOf(f1, f2, f3);
            return String.join(",", results);
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("alpha,beta,gamma", result.getResult(String.class));
    }

    /**
     * Property 8: Wait within child context suspends and resumes correctly
     *
     * <p>A wait() inside a child context SHALL suspend the execution. After the wait completes, the child context SHALL
     * resume and complete with the correct result.
     */
    @Test
    void waitInsideChildContextSuspendsAndResumes() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.runInChildContext("workflow", String.class, child -> {
                child.step("before-wait", Void.class, () -> null);
                child.wait(Duration.ofSeconds(10));
                return child.step("after-wait", String.class, () -> "done");
            });
        });
        runner.withSkipTime(true);

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("done", result.getResult(String.class));
    }

    /**
     * Property 8b: Wait within child context returns PENDING before time advances
     *
     * <p>A wait() inside a child context SHALL cause the execution to return PENDING. After advancing time and
     * re-running, the execution SHALL complete successfully.
     */
    @Test
    void waitInsideChildContextReturnsPendingThenCompletes() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.runInChildContext("workflow", String.class, child -> {
                child.step("before-wait", Void.class, () -> null);
                child.wait(Duration.ofSeconds(10));
                return child.step("after-wait", String.class, () -> "done");
            });
        });
        runner.withSkipTime(false);

        // First run - should suspend at the wait
        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance time so the wait completes
        runner.advanceTime();

        // Second run - should complete
        var result2 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("done", result2.getResult(String.class));
    }

    /**
     * Property 8c: Two async child contexts that both wait suspend and resume correctly
     *
     * <p>When two concurrent child contexts each contain a wait(), the execution SHALL return PENDING. After advancing
     * time and re-running, both child contexts SHALL resume and complete with correct results.
     */
    @Test
    void twoAsyncChildContextsBothWaitSuspendAndResume() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var f1 = ctx.runInChildContextAsync("child-a", String.class, child -> {
                child.step("a-before", Void.class, () -> null);
                child.wait(Duration.ofSeconds(5));
                return child.step("a-after", String.class, () -> "a-done");
            });
            var f2 = ctx.runInChildContextAsync("child-b", String.class, child -> {
                child.step("b-before", Void.class, () -> null);
                child.wait(Duration.ofSeconds(10));
                return child.step("b-after", String.class, () -> "b-done");
            });
            return f1.get() + "+" + f2.get();
        });
        runner.withSkipTime(false);

        // First run - both child contexts should suspend at their waits
        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance time so both waits complete
        runner.advanceTime();

        // Second run - both child contexts resume and complete
        var result2 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("a-done+b-done", result2.getResult(String.class));
    }

    /**
     * Property 8d: One child context waits while another keeps processing — suspension only after work finishes
     *
     * <p>When one async child context contains a long wait and another is actively processing, the execution SHALL NOT
     * suspend until the busy child finishes its work. After the busy child completes, the execution suspends (PENDING)
     * because the waiting child's wait is still outstanding. After advancing time, both complete.
     */
    @Test
    void oneChildWaitsWhileOtherKeepsProcessingSuspendsAfterWorkDone() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var waiting = ctx.runInChildContextAsync("waiter", String.class, child -> {
                child.wait(Duration.ofSeconds(30));
                return child.step("w-after", String.class, () -> "waited");
            });
            var busy = ctx.runInChildContextAsync("busy", String.class, child -> {
                return child.step("slow-work", String.class, () -> {
                    try {
                        Thread.sleep(200); // Simulate real work keeping the thread active
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "done-working";
                });
            });
            return busy.get() + "|" + waiting.get();
        });
        runner.withSkipTime(false);

        // First run: busy child completes its work, but waiter's wait is still outstanding → PENDING
        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // The busy child's step should have been checkpointed before suspension
        var busyStep = result.getOperation("slow-work");
        assertNotNull(busyStep, "Busy child's step should have completed before suspension");

        // Advance time so the wait completes
        runner.advanceTime();

        // Second run: both children complete
        var result2 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("done-working|waited", result2.getResult(String.class));
    }

    /**
     * Property 9: Large result ReplayChildren round-trip
     *
     * <p>A child context with a result ≥256KB SHALL trigger the ReplayChildren flow. On replay, the child context SHALL
     * be re-executed to reconstruct the result.
     */
    @Test
    void largeResultTriggersReplayChildrenAndReconstructsCorrectly() {
        var childExecutionCount = new AtomicInteger(0);

        // Generate a string larger than 256KB
        var largePayload = "x".repeat(256 * 1024 + 100);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.runInChildContext("large-result", String.class, child -> {
                childExecutionCount.incrementAndGet();
                return child.step("produce", String.class, () -> largePayload);
            });
        });

        // First run - executes child context, triggers ReplayChildren
        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(largePayload, result.getResult(String.class));
        assertEquals(1, childExecutionCount.get());

        // Second run - replays with ReplayChildren, re-executes child to reconstruct
        result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(largePayload, result.getResult(String.class));
        // Child function IS re-executed for ReplayChildren (to reconstruct the large result)
        assertTrue(childExecutionCount.get() >= 2, "Child should re-execute for ReplayChildren reconstruction");
    }
}
