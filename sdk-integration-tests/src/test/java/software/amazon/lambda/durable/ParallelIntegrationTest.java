// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.TestOperation;

class ParallelIntegrationTest {

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testSimpleParallel(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("process-items", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 6", "NESTED, 10"})
    void testParallelWithStepsInsideBranches(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("parallel-with-steps", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testParallelPartialFailure_failedBranchDoesNotPreventOthers(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("partial-fail", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 6"})
    void testParallelAllBranchesFail(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            ParallelDurableFuture parallel = context.parallel("all-fail", config);

            try (parallel) {
                parallel.branch("branch-x", String.class, ctx -> {
                    throw new RuntimeException("fail-x");
                });
                parallel.branch("branch-y", String.class, ctx -> {
                    throw new RuntimeException("fail-y");
                });
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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 10", "NESTED, 18"})
    void testParallelWithMaxConcurrency1_sequentialExecution(NestingType nestingType, int events) {
        var peakConcurrency = new AtomicInteger(0);
        var currentConcurrency = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .maxConcurrency(1)
                    .nestingType(nestingType)
                    .build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("sequential-parallel", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 12", "NESTED, 22"})
    void testParallelWithMaxConcurrency2_limitedConcurrency(NestingType nestingType, int events) {
        var peakConcurrency = new AtomicInteger(0);
        var currentConcurrency = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .maxConcurrency(2)
                    .nestingType(nestingType)
                    .build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("limited-parallel", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testParallelReplayAfterInterruption_cachedResultsUsed(NestingType nestingType, int events) {
        var executionCounts = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("replay-parallel", config);

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
        assertEquals(events, result1.getHistoryEvents().size());
        var firstRunCount = executionCounts.get();
        assertTrue(firstRunCount >= 3, "Expected at least 3 executions on first run but got " + firstRunCount);
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 10", "NESTED, 14"})
    void testParallelWithWaitInsideBranches(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("parallel-with-wait", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 8", "NESTED, 12"})
    void testParallelAsyncWithInterleavedWork(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 6", "NESTED, 10"})
    void testStepBeforeAndAfterParallel(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var before = context.step("before", String.class, stepCtx -> "BEFORE");

            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("middle-parallel", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 4", "NESTED, 12"})
    void testSequentialParallelBlocks(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var futures1 = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel1 = context.parallel(
                    "parallel-1",
                    ParallelConfig.builder().nestingType(nestingType).build());
            try (parallel1) {
                futures1.add(parallel1.branch("branch-a", String.class, ctx -> "A"));
                futures1.add(parallel1.branch("branch-b", String.class, ctx -> "B"));
            }

            var futures2 = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel2 = context.parallel(
                    "parallel-2",
                    ParallelConfig.builder().nestingType(nestingType).build());
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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testParallelReplayWithFailedBranches(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("replay-fail-parallel", config);

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

            parallel.get();
            assertEquals("OK", futures.get(0).get());
            assertEquals("OK2", futures.get(2).get());
            return "done";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals(events, result1.getHistoryEvents().size());
        var firstRunCount = executionCount.get();

        // Replay — branch functions should not re-execute
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(events, result2.getHistoryEvents().size());
        if (nestingType == NestingType.FLAT) {
            assertEquals(firstRunCount * 2, executionCount.get(), "Branch functions should re-execute on replay");
        } else {
            assertEquals(firstRunCount, executionCount.get(), "Branch functions should not re-execute on replay");
        }
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 4", "NESTED, 6"})
    void testParallelWithSingleBranch(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("single-branch", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 10", "NESTED, 14"})
    void testParallelWithWaitInsideBranches_replay(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("wait-replay-parallel", config);

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
        assertEquals(events, result1.getHistoryEvents().size());
        var firstRunCount = executionCount.get();
        assertEquals(4, firstRunCount);

        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("A-done,B-done", result2.getResult(String.class));
        if (nestingType == NestingType.FLAT) {
            assertEquals(6, executionCount.get(), "Branch functions should re-execute on replay");
        } else {
            assertEquals(firstRunCount, executionCount.get(), "Branch functions should not re-execute on replay");
        }
        assertEquals(events, result2.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 12"})
    void testParallelUnlimitedConcurrencyWithToleratedFailureCount(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .completionConfig(CompletionConfig.toleratedFailureCount(1))
                    .nestingType(nestingType)
                    .build();
            ParallelDurableFuture parallel = context.parallel("unlimited-tolerated", config);

            try (parallel) {
                parallel.branch("branch-ok1", String.class, ctx -> "OK1");
                parallel.branch("branch-fail1", String.class, ctx -> {
                    throw new RuntimeException("failed: fail1");
                });
                parallel.branch("branch-ok2", String.class, ctx -> "OK2");
                parallel.branch("branch-fail2", String.class, ctx -> {
                    throw new RuntimeException("failed: fail2");
                });
                parallel.branch("branch-ok3", String.class, ctx -> "OK3");
            }

            var result = parallel.get();
            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, result.completionStatus());
            assertFalse(result.completionStatus().isSucceeded());
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 6"})
    void testParallelBranchesReturnDifferentTypes(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            ParallelDurableFuture parallel = context.parallel("mixed-types", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 12"})
    void testParallelResultSummary_succeededAndFailedCounts(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            ParallelDurableFuture parallel = context.parallel("count-check", config);

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    // ---- 50-branch parallel tests with waitForCallback ----

    @ParameterizedTest
    @CsvSource({"FLAT, 302", "NESTED, 402"})
    void testParallel50BranchesWithWaitForCallback(NestingType nestingType, int events) {
        var branchCount = 50;

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("50-callbacks", config);

            try (parallel) {
                for (int i = 0; i < branchCount; i++) {
                    var idx = i;
                    futures.add(parallel.branch("branch-" + i, String.class, ctx -> {
                        return ctx.waitForCallback("approval-" + idx, String.class, (callbackId, stepCtx) -> {});
                    }));
                }
            }

            var result = parallel.get();
            assertEquals(branchCount, result.size());
            assertEquals(branchCount, result.succeeded());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());

            return futures.stream()
                    .map(DurableFuture::get)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
        });

        // First run — all branches create callbacks and suspend
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete all 50 callbacks
        for (int i = 0; i < branchCount; i++) {
            var callbackId = runner.getCallbackId("approval-" + i + "-callback");
            assertNotNull(callbackId, "Callback ID should exist for approval-" + i + "-callback");
            runner.completeCallback(callbackId, "\"result-" + i + "\"");
        }

        // Re-run — all callbacks resolved, execution completes
        result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Verify all 50 results are present
        var output = result.getResult(String.class);
        for (int i = 0; i < branchCount; i++) {
            assertTrue(output.contains("result-" + i), "Output should contain result-" + i);
        }
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 302", "NESTED, 402"})
    void testParallel50BranchesWithWaitForCallback_maxConcurrency5(NestingType nestingType, int events) {
        var branchCount = 50;

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .maxConcurrency(5)
                    .nestingType(nestingType)
                    .build();
            ParallelDurableFuture parallel = context.parallel("50-callbacks-limited", config);

            try (parallel) {
                for (int i = 0; i < branchCount; i++) {
                    var idx = i;
                    parallel.branch("branch-" + i, String.class, ctx -> {
                        return ctx.waitForCallback("cb-" + idx, String.class, (callbackId, stepCtx) -> {});
                    });
                }
            }

            var result = parallel.get();
            assertEquals(branchCount, result.succeeded());
            return String.valueOf(result.succeeded());
        });

        // First run — suspends on callbacks
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete callbacks in batches, re-running between batches to let concurrency-limited branches start
        for (int batch = 0; batch < 10; batch++) {
            var completed = false;
            for (int i = batch * 5; i < (batch + 1) * 5; i++) {
                var callbackId = runner.getCallbackId("cb-" + i + "-callback");
                if (callbackId != null) {
                    runner.completeCallback(callbackId, "\"ok-" + i + "\"");
                    completed = true;
                }
            }
            if (completed) {
                result = runner.run("test");
                if (result.getStatus() == ExecutionStatus.SUCCEEDED) break;
            }
        }

        // Final run to ensure completion
        result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("50", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 302", "NESTED, 402"})
    void testParallel50BranchesWithWaitForCallback_partialFailure(NestingType nestingType, int events) {
        var branchCount = 50;

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            ParallelDurableFuture parallel = context.parallel("50-callbacks-partial-fail", config);

            try (parallel) {
                for (int i = 0; i < branchCount; i++) {
                    var idx = i;
                    parallel.branch("branch-" + i, String.class, ctx -> {
                        return ctx.waitForCallback("approval-" + idx, String.class, (callbackId, stepCtx) -> {});
                    });
                }
            }

            var result = parallel.get();
            assertEquals(branchCount, result.size());
            // Even-indexed branches succeed, odd-indexed branches fail
            assertEquals(25, result.succeeded());
            assertEquals(25, result.failed());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());

            return result.succeeded() + "/" + result.failed();
        });

        // First run — all branches create callbacks and suspend
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete even-indexed callbacks, fail odd-indexed ones
        for (int i = 0; i < branchCount; i++) {
            var callbackId = runner.getCallbackId("approval-" + i + "-callback");
            assertNotNull(callbackId, "Callback ID should exist for approval-" + i);
            if (i % 2 == 0) {
                runner.completeCallback(callbackId, "\"ok-" + i + "\"");
            } else {
                runner.failCallback(
                        callbackId,
                        software.amazon.awssdk.services.lambda.model.ErrorObject.builder()
                                .errorType("Rejected")
                                .errorMessage("Branch " + i + " rejected")
                                .build());
            }
        }

        result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("25/25", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 502", "NESTED, 602"})
    void testParallel50BranchesWithWaitForCallback_stepsBeforeAndAfterCallback(NestingType nestingType, int events) {
        var branchCount = 50;

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            ParallelDurableFuture parallel = context.parallel("50-callbacks-with-steps", config);

            try (parallel) {
                for (int i = 0; i < branchCount; i++) {
                    var idx = i;
                    parallel.branch("branch-" + i, String.class, ctx -> {
                        var before = ctx.step("prepare-" + idx, String.class, stepCtx -> "prepared-" + idx);
                        var approval =
                                ctx.waitForCallback("approval-" + idx, String.class, (callbackId, stepCtx) -> {});
                        return ctx.step("finalize-" + idx, String.class, stepCtx -> before + ":" + approval + ":done");
                    });
                }
            }

            var result = parallel.get();
            assertEquals(branchCount, result.succeeded());
            return String.valueOf(result.succeeded());
        });

        // First run — branches execute prepare step, create callbacks, suspend
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete all callbacks
        for (int i = 0; i < branchCount; i++) {
            var callbackId = runner.getCallbackId("approval-" + i + "-callback");
            assertNotNull(callbackId, "Callback ID should exist for approval-" + i);
            runner.completeCallback(callbackId, "\"approved-" + i + "\"");
        }

        // Re-run — callbacks resolved, finalize steps execute
        result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("50", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    // ---- 50-branch parallel tests with waitForCondition ----

    @ParameterizedTest
    @CsvSource({"FLAT, 200", "NESTED, 300"})
    void testParallel50BranchesWithWaitForCondition(NestingType nestingType, int events) {
        var branchCount = 50;
        var checkCounts = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            var futures = new ArrayList<DurableFuture<Integer>>();
            ParallelDurableFuture parallel = context.parallel("50-conditions", config);

            try (parallel) {
                for (int i = 0; i < branchCount; i++) {
                    var targetChecks = (i % 3) + 1; // 1, 2, or 3 checks to complete
                    futures.add(parallel.branch("branch-" + i, Integer.class, ctx -> {
                        var strategy = WaitStrategies.<Integer>fixedDelay(10, Duration.ofSeconds(1));
                        var wfcConfig = WaitForConditionConfig.<Integer>builder()
                                .waitStrategy(strategy)
                                .build();

                        return ctx.waitForCondition(
                                "poll-" + targetChecks,
                                Integer.class,
                                (state, stepCtx) -> {
                                    checkCounts.incrementAndGet();
                                    var next = (state == null ? 0 : state) + 1;
                                    return next >= targetChecks
                                            ? WaitForConditionResult.stopPolling(next)
                                            : WaitForConditionResult.continuePolling(next);
                                },
                                wfcConfig);
                    }));
                }
            }

            var result = parallel.get();
            assertEquals(branchCount, result.size());
            assertEquals(branchCount, result.succeeded());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());

            var sum = futures.stream().mapToInt(DurableFuture::get).sum();
            return String.valueOf(sum);
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Each branch completes after (i%3)+1 checks: 17 branches need 1, 17 need 2, 16 need 3
        // Sum of results: 17*1 + 17*2 + 16*3 = 17 + 34 + 48 = 99
        assertEquals("99", result.getResult(String.class));
        assertTrue(checkCounts.get() >= branchCount, "Should have at least " + branchCount + " checks");
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 152", "NESTED, 252"})
    void testParallel50BranchesWithWaitForCondition_someExceedMaxAttempts(NestingType nestingType, int events) {
        var branchCount = 50;

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            ParallelDurableFuture parallel = context.parallel("50-conditions-some-fail", config);

            try (parallel) {
                for (int i = 0; i < branchCount; i++) {
                    var idx = i;
                    parallel.branch("branch-" + i, Integer.class, ctx -> {
                        // Odd branches: maxAttempts=1 but need 2 checks → will fail
                        // Even branches: maxAttempts=5, need 2 checks → will succeed
                        var maxAttempts = (idx % 2 == 0) ? 5 : 1;
                        var strategy = WaitStrategies.<Integer>fixedDelay(maxAttempts, Duration.ofSeconds(1));
                        var wfcConfig = WaitForConditionConfig.<Integer>builder()
                                .waitStrategy(strategy)
                                .build();

                        return ctx.waitForCondition(
                                "poll-" + idx,
                                Integer.class,
                                (state, stepCtx) -> {
                                    var next = (state == null ? 0 : state) + 1;
                                    return next >= 2
                                            ? WaitForConditionResult.stopPolling(next)
                                            : WaitForConditionResult.continuePolling(next);
                                },
                                wfcConfig);
                    });
                }
            }

            var result = parallel.get();
            assertEquals(branchCount, result.size());
            assertEquals(25, result.succeeded());
            assertEquals(25, result.failed());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());

            return result.succeeded() + "/" + result.failed();
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("25/25", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 102", "NESTED, 202"})
    void testParallel50BranchesWithWaitForCondition_replay(NestingType nestingType, int events) {
        var branchCount = 50;
        var checkCounts = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            ParallelDurableFuture parallel = context.parallel("50-conditions-replay", config);

            try (parallel) {
                for (int i = 0; i < branchCount; i++) {
                    parallel.branch("branch-" + i, String.class, ctx -> {
                        var strategy = WaitStrategies.<Integer>fixedDelay(5, Duration.ofSeconds(1));
                        var wfcConfig = WaitForConditionConfig.<Integer>builder()
                                .waitStrategy(strategy)
                                .build();

                        var polled = ctx.waitForCondition(
                                "poll",
                                Integer.class,
                                (state, stepCtx) -> {
                                    checkCounts.incrementAndGet();
                                    return WaitForConditionResult.stopPolling(1);
                                },
                                wfcConfig);

                        return String.valueOf(polled);
                    });
                }
            }

            parallel.get();
            return "done";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals(events, result1.getHistoryEvents().size());
        var firstRunChecks = checkCounts.get();
        assertEquals(branchCount, firstRunChecks);

        // Replay — check functions should not re-execute
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(firstRunChecks, checkCounts.get(), "Check functions should not re-execute on replay");
        assertEquals(events, result2.getHistoryEvents().size());
    }

    // ---- 50-branch parallel tests mixing waitForCallback and waitForCondition ----

    @ParameterizedTest
    @CsvSource({"FLAT, 202", "NESTED, 302"})
    void testParallel50BranchesMixed_callbackAndCondition(NestingType nestingType, int events) {
        var branchCount = 50;

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder().nestingType(nestingType).build();
            ParallelDurableFuture parallel = context.parallel("50-mixed", config);

            try (parallel) {
                for (int i = 0; i < branchCount; i++) {
                    var idx = i;
                    if (i % 2 == 0) {
                        // Even branches: waitForCallback
                        parallel.branch("branch-" + i, String.class, ctx -> {
                            return ctx.waitForCallback("cb-" + idx, String.class, (callbackId, stepCtx) -> {});
                        });
                    } else {
                        // Odd branches: waitForCondition
                        parallel.branch("branch-" + i, String.class, ctx -> {
                            var strategy = WaitStrategies.<Integer>fixedDelay(5, Duration.ofSeconds(1));
                            var wfcConfig = WaitForConditionConfig.<Integer>builder()
                                    .waitStrategy(strategy)
                                    .build();

                            var polled = ctx.waitForCondition(
                                    "poll-" + idx,
                                    Integer.class,
                                    (state, stepCtx) -> WaitForConditionResult.stopPolling(idx),
                                    wfcConfig);

                            return "polled-" + polled;
                        });
                    }
                }
            }

            var result = parallel.get();
            assertEquals(branchCount, result.size());
            return String.valueOf(result.succeeded());
        });

        // First run — callback branches suspend, condition branches may complete
        var result = runner.run("test");

        // Complete all callback branches (even-indexed)
        for (int i = 0; i < branchCount; i += 2) {
            var callbackId = runner.getCallbackId("cb-" + i + "-callback");
            if (callbackId != null) {
                runner.completeCallback(callbackId, "\"callback-" + i + "\"");
            }
        }

        result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("50", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testParallelWithToleratedFailureCount_earlyTermination(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.toleratedFailureCount(1))
                    .nestingType(nestingType)
                    .build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("tolerated-fail", config);

            try (parallel) {
                futures.add(parallel.branch("branch-ok", String.class, ctx -> "OK"));
                futures.add(parallel.branch("branch-fail1", String.class, ctx -> {
                    throw new RuntimeException("failed: fail1");
                }));
                futures.add(parallel.branch("branch-fail2", String.class, ctx -> {
                    throw new RuntimeException("failed: fail2");
                }));
                futures.add(parallel.branch("branch-ok2", String.class, ctx -> "OK2"));
            }

            var result = parallel.get();
            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, result.completionStatus());
            assertFalse(result.completionStatus().isSucceeded());
            assertEquals(4, result.size());
            assertEquals("OK", futures.get(0).get());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 6"})
    void testParallelWithMinSuccessful_earlyTermination(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.minSuccessful(2))
                    .nestingType(nestingType)
                    .build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("min-successful", config);

            try (parallel) {
                for (var item : List.of("a", "b", "c", "d", "e")) {
                    futures.add(parallel.branch("branch-" + item, String.class, ctx -> item.toUpperCase()));
                }
            }

            var result = parallel.get();
            assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.completionStatus());
            assertTrue(result.completionStatus().isSucceeded());
            assertTrue(result.size() >= 2 && result.size() <= 5);
            assertEquals("A", futures.get(0).get());
            assertEquals("B", futures.get(1).get());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(
                ExecutionStatus.SUCCEEDED,
                result.getStatus(),
                String.join(
                        " ",
                        result.getOperations().stream()
                                .map(TestOperation::toString)
                                .toList()));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testParallelWithMinSuccessful_earlyTermination_consistentResult(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);
        var initialResult = new AtomicReference<ParallelResult>();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .maxConcurrency(5)
                    .completionConfig(CompletionConfig.minSuccessful(2))
                    .nestingType(nestingType)
                    .build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("min-successful", config);

            try (parallel) {
                for (var item : List.of("a", "b", "c", "d", "e")) {
                    futures.add(parallel.branch("branch-" + item, String.class, ctx -> {
                        executionCount.incrementAndGet();
                        try {
                            if (executionCount.get() <= 5 && (item.equals("a") || item.equals("b"))
                                    || executionCount.get() > 5 && (item.equals("c") || item.equals("d"))) {
                                Thread.sleep(1000);
                            }
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                        return item.toUpperCase();
                    }));
                }
            }

            var result = parallel.get();
            if (initialResult.get() == null) {
                initialResult.set(result);
            } else {
                assertEquals(initialResult.get(), result);
            }
            assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.completionStatus());
            assertTrue(result.completionStatus().isSucceeded());
            assertTrue(result.size() >= 2 && result.size() <= 5);
            assertEquals(ParallelResult.Status.SKIPPED, result.statuses().get(0));
            assertEquals(ParallelResult.Status.SKIPPED, result.statuses().get(1));

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        if (nestingType == NestingType.FLAT) {
            assertEquals(events, result.getHistoryEvents().size());
        } else {
            assertTrue(events <= result.getHistoryEvents().size());
            assertTrue(result.getHistoryEvents().size() <= events + 2);
        }

        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        if (nestingType == NestingType.FLAT) {
            assertEquals(events, result2.getHistoryEvents().size());
        } else {
            assertTrue(result2.getHistoryEvents().size() <= events + 2);
        }
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 6"})
    void testParallelWithAllSuccessful_stopsOnFirstFailure(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.allSuccessful())
                    .nestingType(nestingType)
                    .build();
            var futures = new ArrayList<DurableFuture<String>>();
            ParallelDurableFuture parallel = context.parallel("all-successful", config);

            try (parallel) {
                futures.add(parallel.branch("branch-ok1", String.class, ctx -> "OK1"));
                futures.add(parallel.branch("branch-fail", String.class, ctx -> {
                    throw new RuntimeException("failed");
                }));
                futures.add(parallel.branch("branch-ok2", String.class, ctx -> "OK2"));
            }

            var result = parallel.get();
            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, result.completionStatus());
            assertEquals("OK1", futures.get(0).get());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testParallelWithFirstSuccessful_earlyTermination(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // Use unlimited concurrency so all branches start before early termination fires,
            // avoiding mid-execution suspension that would leave the runner PENDING
            var config = ParallelConfig.builder()
                    .completionConfig(CompletionConfig.firstSuccessful())
                    .nestingType(nestingType)
                    .build();
            ParallelDurableFuture parallel = context.parallel("first-successful", config);

            try (parallel) {
                for (var item : List.of("a", "b", "c")) {
                    parallel.branch("branch-" + item, String.class, ctx -> item.toUpperCase());
                }
            }

            var result = parallel.get();
            assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.completionStatus());
            assertTrue(result.completionStatus().isSucceeded());
            assertEquals(3, result.size());
            assertTrue(result.succeeded() <= 3);
            assertTrue(1 <= result.succeeded());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        if (nestingType == NestingType.FLAT) {
            assertEquals(2, result.getHistoryEvents().size());
        } else {
            // might 4 if only 1 branch completed and at most 8 if all branches completed
            assertTrue(4 <= result.getHistoryEvents().size());
            assertTrue(result.getHistoryEvents().size() <= events);
        }
    }
}
