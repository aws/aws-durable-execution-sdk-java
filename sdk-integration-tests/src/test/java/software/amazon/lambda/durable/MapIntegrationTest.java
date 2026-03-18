// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.CompletionReason;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.MapResultItem;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MapIntegrationTest {

    @Test
    void testSimpleMap() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c");
            var result = context.map("process-items", items, String.class, (item, index, ctx) -> {
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
            var result = context.map("map-with-steps", items, String.class, (item, index, ctx) -> {
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
            var result = context.map("partial-fail", items, String.class, (item, index, ctx) -> {
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
            assertTrue(result.getError(1).errorMessage().contains("item failed"));
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
            var result = context.map("multi-fail", items, String.class, (item, index, ctx) -> {
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
            assertTrue(result.getError(1).errorMessage().contains("bad1"));
            assertNull(result.getResult(3));
            assertNotNull(result.getError(3));
            assertTrue(result.getError(3).errorMessage().contains("bad2"));

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
            var result = context.map("all-fail", items, String.class, (item, index, ctx) -> {
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
            assertTrue(result.getError(0).errorMessage().contains("fail-x"));
            assertTrue(result.getError(1).errorMessage().contains("fail-y"));

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
                    (item, index, ctx) -> {
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
                    (item, index, ctx) -> {
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
        assertTrue(peakConcurrency.get() <= 2, "Expected peak concurrency <= 2 but was " + peakConcurrency.get());
    }

    @Test
    void testMapWithToleratedFailureCount_earlyTermination() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok", "FAIL1", "FAIL2", "ok2", "ok3");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.toleratedFailureCount(1))
                    .build();
            var result = context.map(
                    "tolerated-fail",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        if (item.startsWith("FAIL")) {
                            throw new RuntimeException("failed: " + item);
                        }
                        return item.toUpperCase();
                    },
                    config);

            assertEquals(CompletionReason.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
            assertFalse(result.allSucceeded());
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
            var items = List.of("a", "b", "c", "d", "e");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.minSuccessful(2))
                    .build();
            var result = context.map(
                    "min-successful", items, String.class, (item, index, ctx) -> item.toUpperCase(), config);

            assertEquals(CompletionReason.MIN_SUCCESSFUL_REACHED, result.completionReason());
            assertEquals(5, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));

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
            var result = context.map("replay-map", items, String.class, (item, index, ctx) -> {
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

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("A,B,C", result1.getResult(String.class));
        var firstRunCount = executionCounts.get();
        assertTrue(firstRunCount >= 3, "Expected at least 3 executions on first run but got " + firstRunCount);

        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("A,B,C", result2.getResult(String.class));
        assertEquals(firstRunCount, executionCounts.get(), "Map functions should not re-execute on replay");
    }

    @Test
    void testNestedMap_mapInsideMapBranch() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var outerItems = List.of("group1", "group2");
            var outerResult = context.map("outer-map", outerItems, String.class, (group, outerIndex, outerCtx) -> {
                var innerItems = List.of(group + "-a", group + "-b");
                var innerResult = outerCtx.map(
                        "inner-map-" + outerIndex,
                        innerItems,
                        String.class,
                        (item, innerIndex, innerCtx) -> item.toUpperCase());

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

    @Test
    void testMapWithWaitInsideBranches() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var result = context.map("map-with-wait", items, String.class, (item, index, ctx) -> {
                var stepped = ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                ctx.wait("pause-" + index, Duration.ofSeconds(1));
                return stepped + "-done";
            });

            assertTrue(result.allSucceeded());
            assertEquals("A-done", result.getResult(0));
            assertEquals("B-done", result.getResult(1));
            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A-done,B-done", result.getResult(String.class));
    }

    @Test
    void testMapAsyncWithInterleavedWork() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("x", "y");
            var future = context.mapAsync("async-map", items, String.class, (item, index, ctx) -> {
                return ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
            });

            // Do other work while map runs
            var other = context.step("other-work", String.class, stepCtx -> "OTHER");

            // Now collect map results
            var mapResult = future.get();
            assertTrue(mapResult.allSucceeded());

            return other + ":" + String.join(",", mapResult.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("OTHER:X,Y", result.getResult(String.class));
    }

    @Test
    void testMapUnlimitedConcurrencyWithToleratedFailureCount() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok1", "FAIL1", "ok2", "FAIL2", "ok3");
            var config = MapConfig.builder()
                    .completionConfig(CompletionConfig.toleratedFailureCount(1))
                    .build();
            var result = context.map(
                    "unlimited-tolerated",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        if (item.startsWith("FAIL")) {
                            throw new RuntimeException("failed: " + item);
                        }
                        return item.toUpperCase();
                    },
                    config);

            assertEquals(CompletionReason.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
            assertFalse(result.allSucceeded());
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapReplayWithFailedBranches() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok", "FAIL", "ok2");
            var result = context.map("replay-fail-map", items, String.class, (item, index, ctx) -> {
                executionCount.incrementAndGet();
                if ("FAIL".equals(item)) {
                    throw new RuntimeException("item failed");
                }
                return item.toUpperCase();
            });

            // Errors survive replay since they are stored as ErrorObject (not raw Throwable)
            assertEquals("OK", result.getResult(0));
            assertEquals("OK2", result.getResult(2));
            return "done";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        var firstRunCount = executionCount.get();

        // Replay — functions should not re-execute
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(firstRunCount, executionCount.get(), "Map functions should not re-execute on replay");
    }

    @Test
    void testMapWithSingleItem() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("only");
            var result = context.map("single-item", items, String.class, (item, index, ctx) -> {
                return ctx.step("process", String.class, stepCtx -> item.toUpperCase());
            });

            assertTrue(result.allSucceeded());
            assertEquals(1, result.size());
            assertEquals("ONLY", result.getResult(0));
            assertEquals(0, result.failed().size());
            return result.getResult(0);
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ONLY", result.getResult(String.class));
    }

    @Test
    void testStepBeforeAndAfterMap() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var before = context.step("before", String.class, stepCtx -> "BEFORE");

            var items = List.of("a", "b");
            var mapResult = context.map("middle-map", items, String.class, (item, index, ctx) -> item.toUpperCase());

            var after = context.step("after", String.class, stepCtx -> "AFTER");

            return before + ":" + String.join(",", mapResult.results()) + ":" + after;
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("BEFORE:A,B:AFTER", result.getResult(String.class));
    }

    @Test
    void testSequentialMaps() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result1 =
                    context.map("map-1", List.of("a", "b"), String.class, (item, index, ctx) -> item.toUpperCase());
            var result2 = context.map("map-2", List.of("x", "y"), String.class, (item, index, ctx) -> item + "!");

            return String.join(",", result1.results()) + "|" + String.join(",", result2.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B|x!,y!", result.getResult(String.class));
    }

    @Test
    void testMapWithAllSuccessfulCompletionConfig_stopsOnFirstFailure() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok1", "FAIL", "ok2", "ok3");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.allSuccessful())
                    .build();
            var result = context.map(
                    "all-successful",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        if (item.startsWith("FAIL")) {
                            throw new RuntimeException("failed");
                        }
                        return item.toUpperCase();
                    },
                    config);

            assertEquals(CompletionReason.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
            assertEquals("OK1", result.getResult(0));
            assertNotNull(result.getError(1));
            // Items after the failure should be NOT_STARTED
            assertEquals(MapResultItem.Status.NOT_STARTED, result.getItem(2).status());
            assertEquals(MapResultItem.Status.NOT_STARTED, result.getItem(3).status());
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapWithWaitInsideBranches_replay() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var result = context.map("wait-replay-map", items, String.class, (item, index, ctx) -> {
                executionCount.incrementAndGet();
                var stepped = ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                ctx.wait("pause-" + index, Duration.ofSeconds(1));
                return stepped + "-done";
            });

            assertTrue(result.allSucceeded());
            return String.join(",", result.results());
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("A-done,B-done", result1.getResult(String.class));
        var firstRunCount = executionCount.get();

        // Replay — should use cached results, not re-execute
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("A-done,B-done", result2.getResult(String.class));
        assertEquals(firstRunCount, executionCount.get(), "Map functions should not re-execute on replay");
    }

    @Test
    void testNestedMap_replay() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var outerItems = List.of("g1", "g2");
            var outerResult = context.map("outer", outerItems, String.class, (group, outerIdx, outerCtx) -> {
                var innerItems = List.of(group + "-a", group + "-b");
                var innerResult =
                        outerCtx.map("inner-" + outerIdx, innerItems, String.class, (item, innerIdx, innerCtx) -> {
                            executionCount.incrementAndGet();
                            return item.toUpperCase();
                        });
                return String.join("+", innerResult.results());
            });

            return String.join("|", outerResult.results());
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("G1-A+G1-B|G2-A+G2-B", result1.getResult(String.class));
        var firstRunCount = executionCount.get();

        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("G1-A+G1-B|G2-A+G2-B", result2.getResult(String.class));
        assertEquals(firstRunCount, executionCount.get(), "Nested map should not re-execute on replay");
    }

    @Test
    void testMapWithToleratedFailurePercentage() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok1", "FAIL1", "ok2", "FAIL2", "ok3", "FAIL3", "ok4");
            var config = MapConfig.builder()
                    .completionConfig(CompletionConfig.toleratedFailurePercentage(0.3))
                    .build();
            var result = context.map(
                    "pct-fail",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        if (item.startsWith("FAIL")) {
                            throw new RuntimeException("failed: " + item);
                        }
                        return item.toUpperCase();
                    },
                    config);

            assertEquals(CompletionReason.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapAsyncWithWaitInsideBranches() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var future = context.mapAsync("async-wait-map", items, String.class, (item, index, ctx) -> {
                var stepped = ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                ctx.wait("pause-" + index, Duration.ofSeconds(1));
                return stepped + "-done";
            });

            var other = context.step("other", String.class, stepCtx -> "OTHER");
            var mapResult = future.get();
            assertTrue(mapResult.allSucceeded());

            return other + ":" + String.join(",", mapResult.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("OTHER:A-done,B-done", result.getResult(String.class));
    }

    @Test
    void testMapWithCustomSerDes() {
        var customSerDes = new software.amazon.lambda.durable.serde.JacksonSerDes();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var config = MapConfig.builder().serDes(customSerDes).build();
            var result = context.map(
                    "custom-serdes-map", items, String.class, (item, index, ctx) -> item.toUpperCase(), config);

            assertTrue(result.allSucceeded());
            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B", result.getResult(String.class));
    }

    @Test
    void testMapWithGenericResultType() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a,b", "c,d");
            var result = context.map("generic-map", items, new TypeToken<List<String>>() {}, (item, index, ctx) -> {
                return ctx.step(
                        "split-" + index, new TypeToken<List<String>>() {}, stepCtx -> List.of(item.split(",")));
            });

            assertTrue(result.allSucceeded());
            assertEquals(List.of("a", "b"), result.getResult(0));
            assertEquals(List.of("c", "d"), result.getResult(1));
            return "ok";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @Test
    void testMapInsideParallelBranch() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            try (var parallel =
                    context.parallel("outer-parallel", ParallelConfig.builder().build())) {
                var future1 = parallel.branch("branch-a", String.class, branchCtx -> {
                    var mapResult = branchCtx.map(
                            "map-in-branch-a",
                            List.of("x", "y"),
                            String.class,
                            (item, index, ctx) -> item.toUpperCase());
                    return String.join(",", mapResult.results());
                });
                var future2 = parallel.branch("branch-b", String.class, branchCtx -> {
                    return branchCtx.step("simple-step", String.class, stepCtx -> "DONE");
                });
                parallel.join();
                return future1.get() + "|" + future2.get();
            }
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("X,Y|DONE", result.getResult(String.class));
    }

    @Test
    void testParallelInsideMapBranch() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("group1", "group2");
            var result = context.map("map-with-parallel", items, String.class, (item, index, ctx) -> {
                try (var parallel = ctx.parallel(
                        "parallel-" + index, ParallelConfig.builder().build())) {
                    var f1 = parallel.branch("sub-a-" + index, String.class, bCtx -> {
                        return bCtx.step("step-a-" + index, String.class, stepCtx -> item + "-A");
                    });
                    var f2 = parallel.branch("sub-b-" + index, String.class, bCtx -> {
                        return bCtx.step("step-b-" + index, String.class, stepCtx -> item + "-B");
                    });
                    parallel.join();
                    return f1.get() + "+" + f2.get();
                }
            });

            assertTrue(result.allSucceeded());
            return String.join("|", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("group1-A+group1-B|group2-A+group2-B", result.getResult(String.class));
    }

    @Test
    void testMapWithWaitInsideBranches_maxConcurrency1() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var config = MapConfig.builder().maxConcurrency(1).build();
            var result = context.map(
                    "seq-wait-map",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        var stepped = ctx.step("step-" + index, String.class, stepCtx -> item.toUpperCase());
                        ctx.wait("pause-" + index, Duration.ofSeconds(1));
                        return stepped + "-done";
                    },
                    config);

            assertTrue(result.allSucceeded());
            assertEquals(2, result.size());
            assertEquals("A-done", result.getResult(0));
            assertEquals("B-done", result.getResult(1));
            return String.join(",", result.results());
        });

        // With maxConcurrency=1, each invocation processes one branch's wait.
        // Use explicit run() + advanceTime() loop due to a known thread coordination race
        // (same as ChildContextIntegrationTest.twoAsyncChildContextsBothWaitSuspendAndResume).
        for (int i = 0; i < 10; i++) {
            var result = runner.run("test");
            if (result.getStatus() != ExecutionStatus.PENDING) {
                assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
                assertEquals("A-done,B-done", result.getResult(String.class));
                return;
            }
            runner.advanceTime();
        }
        fail("Expected SUCCEEDED within 10 invocations");
    }
}
