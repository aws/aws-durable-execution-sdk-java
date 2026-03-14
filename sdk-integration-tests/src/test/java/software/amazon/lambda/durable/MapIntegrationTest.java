// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.CompletionReason;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MapIntegrationTest {

    @Test
    void testSimpleMap() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c");
            var result = context.map("process-items", items, String.class, (ctx, item, index) -> {
                return item.toUpperCase();
            });

            assertTrue(result.allSucceeded());
            assertEquals(3, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));
            assertEquals("C", result.getResult(2));

            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B,C", result.getResult(String.class));
    }

    @Test
    void testMapWithStepsInsideBranches() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("hello", "world");
            var result = context.map("map-with-steps", items, String.class, (ctx, item, index) -> {
                return ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
            });

            assertTrue(result.allSucceeded());
            return String.join(" ", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO WORLD", result.getResult(String.class));
    }

    @Test
    void testMapPartialFailure_failedItemDoesNotPreventOthers() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "FAIL", "c");
            var result = context.map("partial-fail", items, String.class, (ctx, item, index) -> {
                if ("FAIL".equals(item)) {
                    throw new RuntimeException("item failed");
                }
                return item.toUpperCase();
            });

            // other items complete despite one failure
            assertFalse(result.allSucceeded());
            assertEquals(3, result.size());

            // failed item captured at corresponding index
            assertEquals("A", result.getResult(0));
            assertNull(result.getResult(1));
            assertNotNull(result.getError(1));
            assertTrue(result.getError(1).getMessage().contains("item failed"));
            assertEquals("C", result.getResult(2));

            // successful items have no error
            assertNull(result.getError(0));
            assertNull(result.getError(2));

            assertEquals(CompletionReason.ALL_COMPLETED, result.completionReason());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapMultipleFailures_allCapturedAtCorrectIndices() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok", "bad1", "ok2", "bad2");
            var result = context.map("multi-fail", items, String.class, (ctx, item, index) -> {
                if (item.startsWith("bad")) {
                    throw new IllegalArgumentException("invalid: " + item);
                }
                return item.toUpperCase();
            });

            assertFalse(result.allSucceeded());
            assertEquals(4, result.size());

            // Successful items
            assertEquals("OK", result.getResult(0));
            assertNull(result.getError(0));
            assertEquals("OK2", result.getResult(2));
            assertNull(result.getError(2));

            // Failed items at correct indices
            assertNull(result.getResult(1));
            assertNotNull(result.getError(1));
            assertTrue(result.getError(1).getMessage().contains("bad1"));
            assertNull(result.getResult(3));
            assertNotNull(result.getError(3));
            assertTrue(result.getError(3).getMessage().contains("bad2"));

            assertEquals(2, result.succeeded().size());
            assertEquals(2, result.failed().size());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapAllItemsFail() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("x", "y");
            var result = context.map("all-fail", items, String.class, (ctx, item, index) -> {
                throw new RuntimeException("fail-" + item);
            });

            assertFalse(result.allSucceeded());
            assertEquals(2, result.size());
            assertEquals(0, result.succeeded().size());
            assertEquals(2, result.failed().size());

            for (int i = 0; i < result.size(); i++) {
                assertNull(result.getResult(i));
                assertNotNull(result.getError(i));
            }
            assertTrue(result.getError(0).getMessage().contains("fail-x"));
            assertTrue(result.getError(1).getMessage().contains("fail-y"));

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapWithMaxConcurrency1_sequentialExecution() {
        var peakConcurrency = new AtomicInteger(0);
        var currentConcurrency = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c", "d");
            var config = MapConfig.builder().maxConcurrency(1).build();
            var result = context.map(
                    "sequential-map",
                    items,
                    String.class,
                    (ctx, item, index) -> {
                        var concurrent = currentConcurrency.incrementAndGet();
                        peakConcurrency.updateAndGet(peak -> Math.max(peak, concurrent));
                        // Simulate some work via a durable step
                        var stepResult = ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                        currentConcurrency.decrementAndGet();
                        return stepResult;
                    },
                    config);

            assertTrue(result.allSucceeded());
            assertEquals(4, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));
            assertEquals("C", result.getResult(2));
            assertEquals("D", result.getResult(3));

            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B,C,D", result.getResult(String.class));
        // With maxConcurrency=1, at most 1 branch should run at a time
        assertTrue(peakConcurrency.get() <= 1, "Expected peak concurrency <= 1 but was " + peakConcurrency.get());
    }

    @Test
    void testMapWithMaxConcurrency2_limitedConcurrency() {
        var peakConcurrency = new AtomicInteger(0);
        var currentConcurrency = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c", "d", "e");
            var config = MapConfig.builder().maxConcurrency(2).build();
            var result = context.map(
                    "limited-map",
                    items,
                    String.class,
                    (ctx, item, index) -> {
                        var concurrent = currentConcurrency.incrementAndGet();
                        peakConcurrency.updateAndGet(peak -> Math.max(peak, concurrent));
                        var stepResult = ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                        currentConcurrency.decrementAndGet();
                        return stepResult;
                    },
                    config);

            assertTrue(result.allSucceeded());
            assertEquals(5, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));
            assertEquals("C", result.getResult(2));
            assertEquals("D", result.getResult(3));
            assertEquals("E", result.getResult(4));

            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B,C,D,E", result.getResult(String.class));
        // With maxConcurrency=2, at most 2 branches should run at a time
        assertTrue(peakConcurrency.get() <= 2, "Expected peak concurrency <= 2 but was " + peakConcurrency.get());
    }

    @Test
    void testMapWithMaxConcurrencyNull_unlimitedConcurrency() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c");
            // Default config has null maxConcurrency (unlimited)
            var result = context.map("unlimited-map", items, String.class, (ctx, item, index) -> {
                return item.toUpperCase();
            });

            assertTrue(result.allSucceeded());
            assertEquals(3, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));
            assertEquals("C", result.getResult(2));

            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B,C", result.getResult(String.class));
    }

    @Test
    void testMapWithMaxConcurrency1_partialFailure() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "FAIL", "c");
            var config = MapConfig.builder().maxConcurrency(1).build();
            var result = context.map(
                    "sequential-partial-fail",
                    items,
                    String.class,
                    (ctx, item, index) -> {
                        if ("FAIL".equals(item)) {
                            throw new RuntimeException("item failed");
                        }
                        return item.toUpperCase();
                    },
                    config);

            assertFalse(result.allSucceeded());
            assertEquals(3, result.size());
            assertEquals("A", result.getResult(0));
            assertNull(result.getResult(1));
            assertNotNull(result.getError(1));
            assertEquals("C", result.getResult(2));

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapWithToleratedFailureCount_earlyTermination() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // 5 items, maxConcurrency=1 so they run sequentially: ok, FAIL1, FAIL2, ok2, ok3
            // toleratedFailureCount=1 means we stop after the 2nd failure
            var items = List.of("ok", "FAIL1", "FAIL2", "ok2", "ok3");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.toleratedFailureCount(1))
                    .build();
            var result = context.map(
                    "tolerated-fail",
                    items,
                    String.class,
                    (ctx, item, index) -> {
                        if (item.startsWith("FAIL")) {
                            throw new RuntimeException("failed: " + item);
                        }
                        return item.toUpperCase();
                    },
                    config);

            assertEquals(CompletionReason.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
            assertFalse(result.allSucceeded());
            // Items after early termination should not have been executed
            assertEquals(5, result.size());
            assertEquals("OK", result.getResult(0));
            assertNull(result.getResult(1));
            assertNotNull(result.getError(1));
            assertNull(result.getResult(2));
            assertNotNull(result.getError(2));

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapWithMinSuccessful_earlyTermination() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            // 5 items, maxConcurrency=1 so they run sequentially
            // minSuccessful=2 means we stop after 2 successes
            var items = List.of("a", "b", "c", "d", "e");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.minSuccessful(2))
                    .build();
            var result = context.map(
                    "min-successful", items, String.class, (ctx, item, index) -> item.toUpperCase(), config);

            assertEquals(CompletionReason.MIN_SUCCESSFUL_REACHED, result.completionReason());
            // First 2 items should have results, remaining should be null (never started)
            assertEquals(5, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapWithFirstSuccessful_stopsAfterFirstSuccess() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.firstSuccessful())
                    .build();
            var result = context.map(
                    "first-successful", items, String.class, (ctx, item, index) -> item.toUpperCase(), config);

            assertEquals(CompletionReason.MIN_SUCCESSFUL_REACHED, result.completionReason());
            assertEquals(3, result.size());
            assertEquals("A", result.getResult(0));
            // Remaining items should not have been started
            assertNull(result.getResult(1));
            assertNull(result.getResult(2));

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapReplayAfterInterruption_cachedResultsUsed() {
        var executionCounts = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c");
            var result = context.map("replay-map", items, String.class, (ctx, item, index) -> {
                executionCounts.incrementAndGet();
                return item.toUpperCase();
            });

            assertTrue(result.allSucceeded());
            assertEquals(3, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));
            assertEquals("C", result.getResult(2));

            return String.join(",", result.results());
        });

        // First execution — all items execute
        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("A,B,C", result1.getResult(String.class));
        var firstRunCount = executionCounts.get();
        assertTrue(firstRunCount >= 3, "Expected at least 3 executions on first run but got " + firstRunCount);

        // Second execution — replay should return cached results without re-executing
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("A,B,C", result2.getResult(String.class));
        assertEquals(firstRunCount, executionCounts.get(), "Map functions should not re-execute on replay");
    }

    @Test
    void testMapReplayWithSteps_cachedStepResultsUsed() {
        var stepExecutionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("hello", "world");
            var result = context.map("replay-steps-map", items, String.class, (ctx, item, index) -> {
                return ctx.step("step-" + index, String.class, stepCtx -> {
                    stepExecutionCount.incrementAndGet();
                    return item.toUpperCase();
                });
            });

            assertTrue(result.allSucceeded());
            return String.join(" ", result.results());
        });

        // First execution
        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("HELLO WORLD", result1.getResult(String.class));
        var firstRunStepCount = stepExecutionCount.get();
        assertTrue(firstRunStepCount >= 2, "Expected at least 2 step executions but got " + firstRunStepCount);

        // Replay — steps should not re-execute
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("HELLO WORLD", result2.getResult(String.class));
        assertEquals(firstRunStepCount, stepExecutionCount.get(), "Steps should not re-execute on replay");
    }

    @Test
    void testNestedMap_mapInsideMapBranch() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var outerItems = List.of("group1", "group2");
            var outerResult = context.map("outer-map", outerItems, String.class, (outerCtx, group, outerIndex) -> {
                // Each outer item runs an inner map
                var innerItems = List.of(group + "-a", group + "-b");
                var innerResult = outerCtx.map(
                        "inner-map-" + outerIndex,
                        innerItems,
                        String.class,
                        (innerCtx, item, innerIndex) -> item.toUpperCase());

                assertTrue(innerResult.allSucceeded());
                return String.join("+", innerResult.results());
            });

            assertTrue(outerResult.allSucceeded());
            assertEquals(2, outerResult.size());
            assertEquals("GROUP1-A+GROUP1-B", outerResult.getResult(0));
            assertEquals("GROUP2-A+GROUP2-B", outerResult.getResult(1));

            var combined = new ArrayList<String>();
            for (int i = 0; i < outerResult.size(); i++) {
                combined.add(outerResult.getResult(i));
            }
            return String.join("|", combined);
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("GROUP1-A+GROUP1-B|GROUP2-A+GROUP2-B", result.getResult(String.class));
    }
}
