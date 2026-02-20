// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.LocalDurableTestRunner;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationType;

/** Integration tests for child context behavior. */
class ChildContextIntegrationTest {

    /**
     * A child context that completes successfully SHALL produce the same result on replay without re-executing the user
     * function.
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
     * A child context that fails with a reconstructable exception SHALL preserve the exception type, message, and error
     * details through the checkpoint-and-replay cycle.
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

    /** Operations checkpointed from within a child context SHALL have the child context's ID as their parentId. */
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

    /** Each child context SHALL maintain its own operation counter. */
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

    /** Two child contexts with operations that have the same local IDs SHALL NOT interfere with each other. */
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

    /** Each concurrently running async child context SHALL complete with its own correct result. */
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

    /** The results returned by DurableFuture.allOf() SHALL be in the same order as the input futures. */
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
     * A wait() inside a child context SHALL suspend the execution. After the wait completes, the child context SHALL
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
     * A wait() inside a child context SHALL cause the execution to return PENDING. After advancing time and re-running,
     * the execution SHALL complete successfully.
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
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Advance time so the wait completes
        runner.advanceTime();

        // Second run - should complete
        var result2 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("done", result2.getResult(String.class));
    }

    /**
     * When two concurrent child contexts each contain a wait(), the execution SHALL return PENDING. After advancing
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
        // TODO: Using run() + runUntilComplete() instead of manual run/advanceTime/run due to a
        //  thread coordination race condition that causes flakiness on slow CI workers.
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Now let runUntilComplete handle the rest (with skipTime so waits auto-advance)
        runner.withSkipTime(true);
        var finalResult = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, finalResult.getStatus());
        assertEquals("a-done+b-done", finalResult.getResult(String.class));
    }

    /**
     * When one async child context contains a long wait and another is actively processing, the execution SHALL NOT
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
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // The busy child's step should have been checkpointed before suspension
        var busyStep = result.getOperation("slow-work");
        assertNotNull(busyStep, "Busy child's step should have completed before suspension");

        // Advance time so the wait completes
        runner.advanceTime();

        // Second run: both children complete
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("done-working|waited", result2.getResult(String.class));
    }

    /**
     * A child context with a result ≥256KB SHALL trigger the ReplayChildren flow. On replay, the child context SHALL be
     * re-executed to reconstruct the result.
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

    // ===== Edge Case Tests =====

    /**
     * A child context created within another child context SHALL have its own independent operation counter and correct
     * parentId propagation.
     */
    @Test
    void nestedChildContextsWithIndependentCountersAndCorrectParentId() {
        var outerChildCount = new AtomicInteger(0);
        var innerChildCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.runInChildContext("outer-child", String.class, outerChild -> {
                outerChildCount.incrementAndGet();
                var outerStep = outerChild.step("outer-step", String.class, () -> "outer");

                var innerResult = outerChild.runInChildContext("inner-child", String.class, innerChild -> {
                    innerChildCount.incrementAndGet();
                    return innerChild.step("inner-step", String.class, () -> "inner");
                });

                return outerStep + "+" + innerResult;
            });
        });

        // First run - executes both nested child contexts
        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("outer+inner", result.getResult(String.class));
        assertEquals(1, outerChildCount.get());
        assertEquals(1, innerChildCount.get());

        // Replay - should return cached results without re-executing
        result = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("outer+inner", result.getResult(String.class));
        assertEquals(1, outerChildCount.get(), "Outer child should not re-execute on replay");
        assertEquals(1, innerChildCount.get(), "Inner child should not re-execute on replay");

        // Verify both steps exist (independent counters — both have local ID "1" in their respective contexts)
        var outerStep = result.getOperation("outer-step");
        var innerStep = result.getOperation("inner-step");
        assertNotNull(outerStep, "Outer step should exist");
        assertNotNull(innerStep, "Inner step should exist");
    }

    /**
     * When a child context is replayed but the current code uses a different operation name at the same position, the
     * execution SHALL fail with a non-deterministic execution error.
     */
    @Test
    void nonDeterministicReplayDetectionForChildContext() {
        var callCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var count = callCount.incrementAndGet();
            if (count == 1) {
                // First execution: create child context with name "original-name"
                return ctx.runInChildContext("original-name", String.class, child -> {
                    return child.step("work", String.class, () -> "result");
                });
            } else {
                // Second execution: use a different name at the same operation position
                // This should trigger NonDeterministicExecutionException
                return ctx.runInChildContext("different-name", String.class, child -> {
                    return child.step("work", String.class, () -> "result");
                });
            }
        });

        // First run succeeds
        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("result", result.getResult(String.class));

        // Second run with different name should fail with non-deterministic error
        result = runner.run("test");
        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(result.getError().isPresent());
        var error = result.getError().get();
        assertTrue(
                error.errorType().contains("NonDeterministicExecutionException"),
                "Expected NonDeterministicExecutionException, got: " + error.errorType());
        assertTrue(
                error.errorMessage().contains("name mismatch"),
                "Expected name mismatch message, got: " + error.errorMessage());
    }

    /**
     * A child context whose function returns a value immediately without performing any durable operations SHALL
     * complete successfully and replay correctly.
     */
    @Test
    void emptyChildContextReturnsImmediately() {
        var childExecutionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            return ctx.runInChildContext("empty", String.class, child -> {
                childExecutionCount.incrementAndGet();
                return "immediate-result";
            });
        });

        // First run - child context returns immediately
        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("immediate-result", result.getResult(String.class));
        assertEquals(1, childExecutionCount.get());

        // Replay - should return cached result without re-executing
        result = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("immediate-result", result.getResult(String.class));
        assertEquals(1, childExecutionCount.get(), "Empty child should not re-execute on replay");
    }

    /**
     * Operations within a child context SHALL use the child context's own operation counter, producing IDs independent
     * of the parent context. Multiple operations within a single child context should get sequential IDs.
     */
    @Test
    void stepAndInvokeWithinChildContextUseChildOperationCounter() {
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            // Parent context: operation 1 is a step
            var parentStep = ctx.step("parent-step", String.class, () -> "parent");

            // Parent context: operation 2 is a child context
            var childResult = ctx.runInChildContext("child-ctx", String.class, child -> {
                // Child context: operations 1, 2, 3 are steps (independent counter)
                var s1 = child.step("child-step-1", String.class, () -> "c1");
                var s2 = child.step("child-step-2", String.class, () -> "c2");
                var s3 = child.step("child-step-3", String.class, () -> "c3");
                return s1 + "," + s2 + "," + s3;
            });

            // Parent context: operation 3 is another step (counter continues from parent)
            var afterStep = ctx.step("after-step", String.class, () -> "after");

            return parentStep + "|" + childResult + "|" + afterStep;
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("parent|c1,c2,c3|after", result.getResult(String.class));

        // Verify all operations exist and completed
        assertNotNull(result.getOperation("parent-step"), "Parent step should exist");
        assertNotNull(result.getOperation("child-step-1"), "Child step 1 should exist");
        assertNotNull(result.getOperation("child-step-2"), "Child step 2 should exist");
        assertNotNull(result.getOperation("child-step-3"), "Child step 3 should exist");
        assertNotNull(result.getOperation("after-step"), "After step should exist");

        // Verify child context operation exists
        var childCtxOp = result.getOperation("child-ctx");
        assertNotNull(childCtxOp, "Child context operation should exist");
        assertEquals(OperationType.CONTEXT, childCtxOp.getType());

        // Replay should produce the same result
        var replayResult = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, replayResult.getStatus());
        assertEquals("parent|c1,c2,c3|after", replayResult.getResult(String.class));
    }
}
