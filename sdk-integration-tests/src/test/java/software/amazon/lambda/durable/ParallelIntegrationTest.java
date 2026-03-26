// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ParallelIntegrationTest {

    @Test
    void testSimpleParallel() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("process-items", config);

            try (parallel) {
                for (var item : List.of("a", "b", "c")) {
                    futures.add(parallel.branch("branch-" + item, String.class, ctx -> item.toUpperCase()));
                }
            }

            var result = parallel.get();
            assertEquals(3, result.size());
            assertEquals(3, result.succeeded());
            assertEquals(0, result.failed());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());

            return String.join(",", futures.stream().map(DurableFuture::get).toList());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B,C", result.getResult(String.class));
    }

    @Test
    void testParallelWithStepsInsideBranches() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("parallel-with-steps", config);

            try (parallel) {
                for (var item : List.of("hello", "world")) {
                    futures.add(parallel.branch(
                            "branch-" + item,
                            String.class,
                            ctx -> ctx.step("process-" + item, String.class, stepCtx -> item.toUpperCase())));
                }
            }

            var result = parallel.get();
            assertTrue(result.completionStatus().isSucceeded());
            return String.join(" ", futures.stream().map(DurableFuture::get).toList());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO WORLD", result.getResult(String.class));
    }

    @Test
    void testParallelPartialFailure_failedBranchDoesNotPreventOthers() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("partial-fail", config);

            try (parallel) {
                futures.add(parallel.branch("branch-a", String.class, ctx -> "A"));
                futures.add(parallel.branch("branch-fail", String.class, ctx -> {
                    throw new RuntimeException("branch failed");
                }));
                futures.add(parallel.branch("branch-c", String.class, ctx -> "C"));
            }

            var result = parallel.get();
            assertEquals(3, result.size());
            assertEquals(2, result.succeeded());
            assertEquals(1, result.failed());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());

            assertEquals("A", futures.get(0).get());
            assertEquals("C", futures.get(2).get());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testParallelAllBranchesFail() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("all-fail", config);

            try (parallel) {
                futures.add(parallel.branch("branch-x", String.class, ctx -> {
                    throw new RuntimeException("fail-x");
                }));
                futures.add(parallel.branch("branch-y", String.class, ctx -> {
                    throw new RuntimeException("fail-y");
                }));
            }

            var result = parallel.get();
            assertEquals(2, result.size());
            assertEquals(0, result.succeeded());
            assertEquals(2, result.failed());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testParallelWithMaxConcurrency1_sequentialExecution() {
        var peakConcurrency = new AtomicInteger(0);
        var currentConcurrency = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().maxConcurrency(1).build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("sequential-parallel", config);

            try (parallel) {
                for (var item : List.of("a", "b", "c", "d")) {
                    futures.add(parallel.branch("branch-" + item, String.class, ctx -> {
                        var concurrent = currentConcurrency.incrementAndGet();
                        peakConcurrency.updateAndGet(peak -> Math.max(peak, concurrent));
                        var stepped = ctx.step("process-" + item, String.class, stepCtx -> item.toUpperCase());
                        currentConcurrency.decrementAndGet();
                        return stepped;
                    }));
                }
            }

            var result = parallel.get();
            assertEquals(4, result.size());
            assertEquals(4, result.succeeded());
            return String.join(",", futures.stream().map(DurableFuture::get).toList());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B,C,D", result.getResult(String.class));
        assertTrue(peakConcurrency.get() <= 1, "Expected peak concurrency <= 1 but was " + peakConcurrency.get());
    }

    @Test
    void testParallelWithMaxConcurrency2_limitedConcurrency() {
        var peakConcurrency = new AtomicInteger(0);
        var currentConcurrency = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().maxConcurrency(2).build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("limited-parallel", config);

            try (parallel) {
                for (var item : List.of("a", "b", "c", "d", "e")) {
                    futures.add(parallel.branch("branch-" + item, String.class, ctx -> {
                        var concurrent = currentConcurrency.incrementAndGet();
                        peakConcurrency.updateAndGet(peak -> Math.max(peak, concurrent));
                        var stepped = ctx.step("process-" + item, String.class, stepCtx -> item.toUpperCase());
                        currentConcurrency.decrementAndGet();
                        return stepped;
                    }));
                }
            }

            var result = parallel.get();
            assertEquals(5, result.size());
            assertEquals(5, result.succeeded());
            return String.join(",", futures.stream().map(DurableFuture::get).toList());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B,C,D,E", result.getResult(String.class));
        assertTrue(peakConcurrency.get() <= 2, "Expected peak concurrency <= 2 but was " + peakConcurrency.get());
    }

    @Test
    void testParallelReplayAfterInterruption_cachedResultsUsed() {
        var executionCounts = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("replay-parallel", config);

            try (parallel) {
                for (var item : List.of("a", "b", "c")) {
                    futures.add(parallel.branch("branch-" + item, String.class, ctx -> {
                        executionCounts.incrementAndGet();
                        return item.toUpperCase();
                    }));
                }
            }

            var result = parallel.get();
            assertEquals(3, result.succeeded());
            return String.join(",", futures.stream().map(DurableFuture::get).toList());
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("A,B,C", result1.getResult(String.class));
        var firstRunCount = executionCounts.get();
        assertTrue(firstRunCount >= 3, "Expected at least 3 executions on first run but got " + firstRunCount);

        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("A,B,C", result2.getResult(String.class));
        assertEquals(firstRunCount, executionCounts.get(), "Branch functions should not re-execute on replay");
    }

    @Test
    void testParallelWithWaitInsideBranches() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("parallel-with-wait", config);

            try (parallel) {
                for (var item : List.of("a", "b")) {
                    futures.add(parallel.branch("branch-" + item, String.class, ctx -> {
                        var stepped = ctx.step("process-" + item, String.class, stepCtx -> item.toUpperCase());
                        ctx.wait("pause-" + item, Duration.ofSeconds(1));
                        return stepped + "-done";
                    }));
                }
            }

            var result = parallel.get();
            assertTrue(result.completionStatus().isSucceeded());
            assertEquals("A-done", futures.get(0).get());
            assertEquals("B-done", futures.get(1).get());
            return String.join(",", futures.stream().map(DurableFuture::get).toList());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A-done,B-done", result.getResult(String.class));
    }

    @Test
    void testParallelAsyncWithInterleavedWork() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("async-parallel", config);

            // Register branches without joining yet
            futures.add(parallel.branch(
                    "branch-x", String.class, ctx -> ctx.step("process-x", String.class, stepCtx -> "X")));
            futures.add(parallel.branch(
                    "branch-y", String.class, ctx -> ctx.step("process-y", String.class, stepCtx -> "Y")));

            // Do other work while parallel runs
            var other = context.step("other-work", String.class, stepCtx -> "OTHER");

            // Now join
            parallel.close();
            var parallelResult = parallel.get();
            assertTrue(parallelResult.completionStatus().isSucceeded());

            return other + ":"
                    + String.join(",", futures.stream().map(DurableFuture::get).toList());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("OTHER:X,Y", result.getResult(String.class));
    }

    @Test
    void testStepBeforeAndAfterParallel() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var before = context.step("before", String.class, stepCtx -> "BEFORE");

            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("middle-parallel", config);

            try (parallel) {
                futures.add(parallel.branch("branch-a", String.class, ctx -> "A"));
                futures.add(parallel.branch("branch-b", String.class, ctx -> "B"));
            }

            var after = context.step("after", String.class, stepCtx -> "AFTER");

            return before + ":"
                    + String.join(",", futures.stream().map(DurableFuture::get).toList()) + ":" + after;
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("BEFORE:A,B:AFTER", result.getResult(String.class));
    }

    @Test
    void testSequentialParallelBlocks() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var futures1 = new ArrayList<DurableFuture<String>>();
            var parallel1 =
                    context.parallel("parallel-1", ParallelConfig.builder().build());
            try (parallel1) {
                futures1.add(parallel1.branch("branch-a", String.class, ctx -> "A"));
                futures1.add(parallel1.branch("branch-b", String.class, ctx -> "B"));
            }

            var futures2 = new ArrayList<DurableFuture<String>>();
            var parallel2 =
                    context.parallel("parallel-2", ParallelConfig.builder().build());
            try (parallel2) {
                futures2.add(parallel2.branch("branch-x", String.class, ctx -> "x!"));
                futures2.add(parallel2.branch("branch-y", String.class, ctx -> "y!"));
            }

            var r1 = String.join(",", futures1.stream().map(DurableFuture::get).toList());
            var r2 = String.join(",", futures2.stream().map(DurableFuture::get).toList());
            return r1 + "|" + r2;
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B|x!,y!", result.getResult(String.class));
    }

    @Test
    void testParallelReplayWithFailedBranches() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("replay-fail-parallel", config);

            try (parallel) {
                futures.add(parallel.branch("branch-ok", String.class, ctx -> {
                    executionCount.incrementAndGet();
                    return "OK";
                }));
                futures.add(parallel.branch("branch-fail", String.class, ctx -> {
                    executionCount.incrementAndGet();
                    throw new RuntimeException("branch failed");
                }));
                futures.add(parallel.branch("branch-ok2", String.class, ctx -> {
                    executionCount.incrementAndGet();
                    return "OK2";
                }));
            }

            var result = parallel.get();
            assertEquals("OK", futures.get(0).get());
            assertEquals("OK2", futures.get(2).get());
            return "done";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        var firstRunCount = executionCount.get();

        // Replay — branch functions should not re-execute
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(firstRunCount, executionCount.get(), "Branch functions should not re-execute on replay");
    }

    @Test
    void testParallelWithSingleBranch() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("single-branch", config);

            try (parallel) {
                futures.add(parallel.branch(
                        "branch-only", String.class, ctx -> ctx.step("process", String.class, stepCtx -> "ONLY")));
            }

            var result = parallel.get();
            assertEquals(1, result.size());
            assertEquals(1, result.succeeded());
            assertEquals(0, result.failed());
            return futures.get(0).get();
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ONLY", result.getResult(String.class));
    }

    @Test
    void testParallelWithWaitInsideBranches_replay() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("wait-replay-parallel", config);

            try (parallel) {
                for (var item : List.of("a", "b")) {
                    futures.add(parallel.branch("branch-" + item, String.class, ctx -> {
                        executionCount.incrementAndGet();
                        var stepped = ctx.step("process-" + item, String.class, stepCtx -> item.toUpperCase());
                        ctx.wait("pause-" + item, Duration.ofSeconds(1));
                        return stepped + "-done";
                    }));
                }
            }

            var result = parallel.get();
            assertTrue(result.completionStatus().isSucceeded());
            return String.join(",", futures.stream().map(DurableFuture::get).toList());
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("A-done,B-done", result1.getResult(String.class));
        var firstRunCount = executionCount.get();

        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("A-done,B-done", result2.getResult(String.class));
        assertEquals(firstRunCount, executionCount.get(), "Branch functions should not re-execute on replay");
    }

    @Test
    void testParallelUnlimitedConcurrencyWithToleratedFailureCount() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .completionConfig(CompletionConfig.toleratedFailureCount(1))
                    .build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("unlimited-tolerated", config);

            try (parallel) {
                futures.add(parallel.branch("branch-ok1", String.class, ctx -> "OK1"));
                futures.add(parallel.branch("branch-fail1", String.class, ctx -> {
                    throw new RuntimeException("failed: fail1");
                }));
                futures.add(parallel.branch("branch-ok2", String.class, ctx -> "OK2"));
                futures.add(parallel.branch("branch-fail2", String.class, ctx -> {
                    throw new RuntimeException("failed: fail2");
                }));
                futures.add(parallel.branch("branch-ok3", String.class, ctx -> "OK3"));
            }

            var result = parallel.get();
            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, result.completionStatus());
            assertFalse(result.completionStatus().isSucceeded());
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testParallelBranchesReturnDifferentTypes() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var parallel = context.parallel("mixed-types", config);

            DurableFuture<String> strFuture;
            DurableFuture<Integer> intFuture;

            try (parallel) {
                strFuture = parallel.branch("branch-str", String.class, ctx -> "hello");
                intFuture = parallel.branch("branch-int", Integer.class, ctx -> 42);
            }

            var result = parallel.get();
            assertEquals(2, result.size());
            assertEquals(2, result.succeeded());
            assertEquals("hello", strFuture.get());
            assertEquals(42, intFuture.get());

            return strFuture.get() + ":" + intFuture.get();
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("hello:42", result.getResult(String.class));
    }

    @Test
    void testParallelResultSummary_succeededAndFailedCounts() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().build();
            var parallel = context.parallel("count-check", config);

            try (parallel) {
                parallel.branch("ok1", String.class, ctx -> "OK1");
                parallel.branch("ok2", String.class, ctx -> "OK2");
                parallel.branch("ok3", String.class, ctx -> "OK3");
                parallel.branch("fail1", String.class, ctx -> {
                    throw new RuntimeException("fail");
                });
                parallel.branch("fail2", String.class, ctx -> {
                    throw new RuntimeException("fail");
                });
            }

            var result = parallel.get();
            assertEquals(5, result.size());
            assertEquals(3, result.succeeded());
            assertEquals(2, result.failed());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());

            return result.succeeded() + "/" + result.failed();
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("3/2", result.getResult(String.class));
    }
}
