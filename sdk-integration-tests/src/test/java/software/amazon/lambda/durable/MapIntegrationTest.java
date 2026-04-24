// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.WaitStrategies;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class MapIntegrationTest {

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testSimpleMap(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c");
            var result = context.map(
                    "process-items",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        return item.toUpperCase();
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            assertEquals(3, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));
            assertEquals("C", result.getResult(2));

            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
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
        assertEquals(10, result.getHistoryEvents().size());
        assertEquals(
                result.getOperation("map-with-steps-iteration-1").getId(),
                result.getOperation("process-1").getEvents().get(0).parentId());
        assertEquals("HELLO WORLD", result.getResult(String.class));
    }

    @Test
    void testFlatMapWithStepsInsideBranches() {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("hello", "world");
            var result = context.map(
                    "map-with-steps",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        return ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                    },
                    MapConfig.builder().nestingType(NestingType.FLAT).build());

            assertTrue(result.allSucceeded());
            return String.join(" ", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(6, result.getHistoryEvents().size());
        assertEquals(
                result.getOperation("map-with-steps").getId(),
                result.getOperation("process-1").getEvents().get(0).parentId());
        assertEquals("HELLO WORLD", result.getResult(String.class));
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testMapPartialFailure_failedItemDoesNotPreventOthers(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "FAIL", "c");
            var result = context.map(
                    "partial-fail",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        if ("FAIL".equals(item)) {
                            throw new RuntimeException("item failed");
                        }
                        return item.toUpperCase();
                    },
                    MapConfig.builder().nestingType(nestingType).build());

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

            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionReason());

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 10"})
    void testMapMultipleFailures_allCapturedAtCorrectIndices(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok", "bad1", "ok2", "bad2");
            var result = context.map(
                    "multi-fail",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        if (item.startsWith("bad")) {
                            throw new IllegalArgumentException("invalid: " + item);
                        }
                        return item.toUpperCase();
                    },
                    MapConfig.builder().nestingType(nestingType).build());

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 6"})
    void testMapAllItemsFail(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("x", "y");
            var result = context.map(
                    "all-fail",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        throw new RuntimeException("fail-" + item);
                    },
                    MapConfig.builder().nestingType(nestingType).build());

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 10", "NESTED, 18"})
    void testMapWithMaxConcurrency1_sequentialExecution(NestingType nestingType, int events) {
        var peakConcurrency = new AtomicInteger(0);
        var currentConcurrency = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c", "d");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .nestingType(nestingType)
                    .build();
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
        assertEquals(events, result.getHistoryEvents().size());
        // With maxConcurrency=1, at most 1 branch should run at a time
        assertTrue(peakConcurrency.get() <= 1, "Expected peak concurrency <= 1 but was " + peakConcurrency.get());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 12", "NESTED, 22"})
    void testMapWithMaxConcurrency2_limitedConcurrency(NestingType nestingType, int events) {
        var peakConcurrency = new AtomicInteger(0);
        var currentConcurrency = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c", "d", "e");
            var config = MapConfig.builder()
                    .maxConcurrency(2)
                    .nestingType(nestingType)
                    .build();
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
        assertEquals(events, result.getHistoryEvents().size());
        assertEquals("A,B,C,D,E", result.getResult(String.class));
        assertTrue(peakConcurrency.get() <= 2, "Expected peak concurrency <= 2 but was " + peakConcurrency.get());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testMapWithToleratedFailureCount_earlyTermination(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok", "FAIL1", "FAIL2", "ok2", "ok3");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.toleratedFailureCount(1))
                    .nestingType(nestingType)
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

            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 6"})
    void testMapWithMinSuccessful_earlyTermination(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c", "d", "e");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.minSuccessful(2))
                    .nestingType(nestingType)
                    .build();
            var result = context.map(
                    "min-successful", items, String.class, (item, index, ctx) -> item.toUpperCase(), config);

            assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.completionReason());
            assertEquals(5, result.size());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testMapReplayAfterInterruption_cachedResultsUsed(NestingType nestingType, int events) {
        var executionCounts = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c");
            var result = context.map(
                    "replay-map",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        executionCounts.incrementAndGet();
                        return item.toUpperCase();
                    },
                    MapConfig.builder().nestingType(nestingType).build());

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
        assertEquals(events, result2.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, FLAT, 6", "NESTED, FLAT, 10", "FLAT, NESTED, 14", "NESTED, NESTED, 18"})
    void testNestedMap_mapInsideMapBranch(NestingType outerNestingType, NestingType innerNestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var outerItems = List.of("group1", "group2");
            var outerResult = context.map(
                    "outer-map",
                    outerItems,
                    String.class,
                    (group, outerIndex, outerCtx) -> {
                        var innerItems = List.of(group + "-a", group + "-b");
                        var innerResult = outerCtx.map(
                                "inner-map-" + outerIndex,
                                innerItems,
                                String.class,
                                (item, innerIndex, innerCtx) -> item.toUpperCase(),
                                MapConfig.builder()
                                        .nestingType(innerNestingType)
                                        .build());

                        assertTrue(innerResult.allSucceeded());
                        return String.join("+", innerResult.results());
                    },
                    MapConfig.builder().nestingType(outerNestingType).build());

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 10", "NESTED, 14"})
    void testMapWithWaitInsideBranches(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var result = context.map(
                    "map-with-wait",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        var stepped = ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                        ctx.wait("pause-" + index, Duration.ofSeconds(1));
                        return stepped + "-done";
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            assertEquals("A-done", result.getResult(0));
            assertEquals("B-done", result.getResult(1));
            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A-done,B-done", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 8", "NESTED, 12"})
    void testMapAsyncWithInterleavedWork(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("x", "y");
            var future = context.mapAsync(
                    "async-map",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        return ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                    },
                    MapConfig.builder().nestingType(nestingType).build());

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
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 12"})
    void testMapUnlimitedConcurrencyWithToleratedFailureCount(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok1", "FAIL1", "ok2", "FAIL2", "ok3");
            var config = MapConfig.builder()
                    .completionConfig(CompletionConfig.toleratedFailureCount(1))
                    .nestingType(nestingType)
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

            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
            assertFalse(result.allSucceeded());
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
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

            // Errors survive replay since they are stored as MapError (not raw Throwable)
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

    @ParameterizedTest
    @CsvSource({"FLAT, 4", "NESTED, 6"})
    void testMapWithSingleItem(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("only");
            var result = context.map(
                    "single-item",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        return ctx.step("process", String.class, stepCtx -> item.toUpperCase());
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            assertEquals(1, result.size());
            assertEquals("ONLY", result.getResult(0));
            assertEquals(0, result.failed().size());
            return result.getResult(0);
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ONLY", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 6", "NESTED, 10"})
    void testStepBeforeAndAfterMap(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var before = context.step("before", String.class, stepCtx -> "BEFORE");

            var items = List.of("a", "b");
            var mapResult = context.map(
                    "middle-map",
                    items,
                    String.class,
                    (item, index, ctx) -> item.toUpperCase(),
                    MapConfig.builder().nestingType(nestingType).build());

            var after = context.step("after", String.class, stepCtx -> "AFTER");

            return before + ":" + String.join(",", mapResult.results()) + ":" + after;
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("BEFORE:A,B:AFTER", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 4", "NESTED, 12"})
    void testSequentialMaps(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result1 = context.map(
                    "map-1",
                    List.of("a", "b"),
                    String.class,
                    (item, index, ctx) -> item.toUpperCase(),
                    MapConfig.builder().nestingType(nestingType).build());
            var result2 = context.map(
                    "map-2",
                    List.of("x", "y"),
                    String.class,
                    (item, index, ctx) -> item + "!",
                    MapConfig.builder().nestingType(nestingType).build());

            return String.join(",", result1.results()) + "|" + String.join(",", result2.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B|x!,y!", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
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

            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
            assertEquals("OK1", result.getResult(0));
            assertNotNull(result.getError(1));
            // Items after the failure should be NOT_STARTED
            assertEquals(
                    MapResult.MapResultItem.Status.SKIPPED, result.getItem(2).status());
            assertEquals(
                    MapResult.MapResultItem.Status.SKIPPED, result.getItem(3).status());
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 10", "NESTED, 14"})
    void testMapWithWaitInsideBranches_replay(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var result = context.map(
                    "wait-replay-map",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        executionCount.incrementAndGet();
                        var stepped = ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                        ctx.wait("pause-" + index, Duration.ofSeconds(1));
                        return stepped + "-done";
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            return String.join(",", result.results());
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("A-done,B-done", result1.getResult(String.class));
        assertEquals(events, result1.getHistoryEvents().size());
        var firstRunCount = executionCount.get();

        // Replay — should use cached results, not re-execute
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("A-done,B-done", result2.getResult(String.class));
        assertEquals(firstRunCount, executionCount.get(), "Map functions should not re-execute on replay");
        assertEquals(events, result2.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, FLAT, 6", "FLAT, NESTED, 14", "NESTED, FLAT, 10", "NESTED, NESTED, 18"})
    void testNestedMap_replay(NestingType outerNestingType, NestingType innerNestingType, int events) {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var outerItems = List.of("g1", "g2");
            var outerResult = context.map(
                    "outer",
                    outerItems,
                    String.class,
                    (group, outerIdx, outerCtx) -> {
                        var innerItems = List.of(group + "-a", group + "-b");
                        var innerResult = outerCtx.map(
                                "inner-" + outerIdx,
                                innerItems,
                                String.class,
                                (item, innerIdx, innerCtx) -> {
                                    executionCount.incrementAndGet();
                                    return item.toUpperCase();
                                },
                                MapConfig.builder()
                                        .nestingType(innerNestingType)
                                        .build());
                        return String.join("+", innerResult.results());
                    },
                    MapConfig.builder().nestingType(outerNestingType).build());

            return String.join("|", outerResult.results());
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("G1-A+G1-B|G2-A+G2-B", result1.getResult(String.class));
        assertEquals(events, result1.getHistoryEvents().size());
        var firstRunCount = executionCount.get();

        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("G1-A+G1-B|G2-A+G2-B", result2.getResult(String.class));
        assertEquals(firstRunCount, executionCount.get(), "Nested map should not re-execute on replay");
        assertEquals(events, result2.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 16"})
    void testMapWithToleratedFailurePercentage(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok1", "FAIL1", "ok2", "FAIL2", "ok3", "FAIL3", "ok4");
            var config = MapConfig.builder()
                    .completionConfig(CompletionConfig.toleratedFailurePercentage(0.3))
                    .nestingType(nestingType)
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

            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 16"})
    void testMapWithToleratedFailurePercentage_replay(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("ok1", "FAIL1", "ok2", "FAIL2", "ok3", "FAIL3", "ok4");
            var config = MapConfig.builder()
                    .completionConfig(CompletionConfig.toleratedFailurePercentage(0.3))
                    .nestingType(nestingType)
                    .build();
            var result = context.map(
                    "pct-fail-replay",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        executionCount.incrementAndGet();
                        if (item.startsWith("FAIL")) {
                            throw new RuntimeException("failed: " + item);
                        }
                        return item.toUpperCase();
                    },
                    config);

            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, result.completionReason());
            return "done";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        var firstRunCount = executionCount.get();
        assertEquals(events, result1.getHistoryEvents().size());

        // Replay — with unlimited concurrency, children replay simultaneously.
        // Verify completionReason is consistent and no re-execution occurs.
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(firstRunCount, executionCount.get(), "Map functions should not re-execute on replay");
        assertEquals(events, result2.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 12", "NESTED, 16"})
    void testMapAsyncWithWaitInsideBranches(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var future = context.mapAsync(
                    "async-wait-map",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        var stepped = ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                        ctx.wait("pause-" + index, Duration.ofSeconds(1));
                        return stepped + "-done";
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            var other = context.step("other", String.class, stepCtx -> "OTHER");
            var mapResult = future.get();
            assertTrue(mapResult.allSucceeded());

            return other + ":" + String.join(",", mapResult.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("OTHER:A-done,B-done", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 6"})
    void testMapWithCustomSerDes(NestingType nestingType, int events) {
        var customSerDes = new JacksonSerDes();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var config = MapConfig.builder()
                    .serDes(customSerDes)
                    .nestingType(nestingType)
                    .build();
            var result = context.map(
                    "custom-serdes-map", items, String.class, (item, index, ctx) -> item.toUpperCase(), config);

            assertTrue(result.allSucceeded());
            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 6", "NESTED, 10"})
    void testMapWithGenericResultType(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a,b", "c,d");
            var result = context.map(
                    "generic-map",
                    items,
                    new TypeToken<List<String>>() {},
                    (item, index, ctx) -> {
                        return ctx.step(
                                "split-" + index,
                                new TypeToken<List<String>>() {},
                                stepCtx -> List.of(item.split(",")));
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            assertEquals(List.of("a", "b"), result.getResult(0));
            assertEquals(List.of("c", "d"), result.getResult(1));
            return "ok";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 10", "NESTED, 14"})
    void testMapWithWaitInsideBranches_maxConcurrency1(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .nestingType(nestingType)
                    .build();
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
                assertEquals(events, result.getHistoryEvents().size());
                return;
            }
            runner.advanceTime();
        }
        fail("Expected SUCCEEDED within 10 invocations");
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 6"})
    void testMapWithMinSuccessful_replay(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c", "d", "e");
            var config = MapConfig.builder()
                    .maxConcurrency(1)
                    .completionConfig(CompletionConfig.minSuccessful(2))
                    .nestingType(nestingType)
                    .build();
            var result = context.map(
                    "min-success-replay",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        executionCount.incrementAndGet();
                        return item.toUpperCase();
                    },
                    config);

            assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, result.completionReason());
            assertEquals("A", result.getResult(0));
            assertEquals("B", result.getResult(1));
            return "done";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        var firstRunCount = executionCount.get();
        assertEquals(events, result1.getHistoryEvents().size());

        // Replay — small result path: deserialize MapResult from payload, no child replay
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(firstRunCount, executionCount.get(), "Map functions should not re-execute on replay");
        assertEquals(events, result2.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 9"})
    void testMapWithMinSuccessful_replayLargePayload(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c", "d", "e");
            var config = MapConfig.builder()
                    .maxConcurrency(5)
                    .completionConfig(CompletionConfig.minSuccessful(2))
                    .nestingType(nestingType)
                    .build();
            var result = context.map(
                    "min-success-replay",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        executionCount.incrementAndGet();
                        // add delays to first 3 items when starting, and
                        // add delays to other 2 items when replaying
                        try {
                            if (executionCount.get() <= 5 && index < 3 || executionCount.get() > 5 && index > 3) {
                                Thread.sleep(1000);
                            }
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return item.toUpperCase().repeat(1000000);
                    },
                    config);

            // always the same items get skipped when replay
            assertEquals(
                    MapResult.MapResultItem.Status.SKIPPED, result.getItem(0).status());
            assertEquals(
                    MapResult.MapResultItem.Status.SKIPPED, result.getItem(1).status());
            assertEquals(
                    MapResult.MapResultItem.Status.SKIPPED, result.getItem(2).status());
            assertEquals(
                    MapResult.MapResultItem.Status.SUCCEEDED, result.getItem(3).status());
            assertEquals(
                    MapResult.MapResultItem.Status.SUCCEEDED, result.getItem(4).status());

            return "done";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        var firstRunCount = executionCount.get();
        assertEquals(events, result1.getHistoryEvents().size());

        // Replay — small result path: deserialize MapResult from payload, no child replay
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(firstRunCount + 2, executionCount.get(), "Map functions should re-execute on replay");
        assertEquals(events, result2.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 9"})
    void testMapWithMinSuccessful_replayLargePayloadResultConsistency(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);
        var initialResult = new AtomicReference<MapResult<String>>();

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c", "d", "e");
            var config = MapConfig.builder()
                    .maxConcurrency(5)
                    .completionConfig(CompletionConfig.minSuccessful(2))
                    .nestingType(nestingType)
                    .build();
            var result = context.map(
                    "min-success-replay",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        executionCount.incrementAndGet();
                        // add delays to first 2 items when starting, and
                        // add delays to other 3 items when replaying
                        try {
                            if (executionCount.get() <= 5 && index < 2 || executionCount.get() > 5 && index > 2) {
                                Thread.sleep(1000);
                            }
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        return item.toUpperCase().repeat(1000000);
                    },
                    config);

            // always the same items get skipped when replay
            if (initialResult.get() == null) {
                initialResult.set(result);
            } else {
                assertEquals(initialResult.get(), result);
            }

            return "done";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        var firstRunCount = executionCount.get();
        if (nestingType == NestingType.FLAT) {
            assertEquals(events, result1.getHistoryEvents().size());
        } else {
            // 9 events if 2 branches completed 10 if 3
            assertTrue(events <= result1.getHistoryEvents().size());
        }

        // Replay — small result path: deserialize MapResult from payload, no child replay
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        // 2 or 3 more replays
        assertTrue(firstRunCount + 2 <= executionCount.get(), "Map functions should re-execute on replay");
        if (nestingType == NestingType.FLAT) {
            assertEquals(events, result2.getHistoryEvents().size());
        } else {
            // 9 events if 2 branches completed 10 if 3
            assertTrue(events <= result2.getHistoryEvents().size());
            assertTrue(result2.getHistoryEvents().size() <= events + 1);
        }
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 8", "NESTED, 12"})
    void testMapAsyncWithInterleavedWork_replay(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("x", "y");
            var future = context.mapAsync(
                    "async-replay-map",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        executionCount.incrementAndGet();
                        return ctx.step("process-" + index, String.class, stepCtx -> item.toUpperCase());
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            var other = context.step("other-work", String.class, stepCtx -> "OTHER");
            var mapResult = future.get();
            assertTrue(mapResult.allSucceeded());

            return other + ":" + String.join(",", mapResult.results());
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals("OTHER:X,Y", result1.getResult(String.class));
        assertEquals(events, result1.getHistoryEvents().size());
        var firstRunCount = executionCount.get();

        // Replay — async map + interleaved step should all use cached results
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals("OTHER:X,Y", result2.getResult(String.class));
        assertEquals(firstRunCount, executionCount.get(), "Map functions should not re-execute on replay");
        assertEquals(events, result2.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 202"})
    void testMapWithLargeResult_replayChildren(NestingType nestingType, int events) {
        var executionCount = new AtomicInteger(0);
        // Generate items that produce results exceeding 256KB total to trigger replayChildren path
        var items = new ArrayList<String>();
        for (int i = 0; i < 100; i++) {
            items.add("item-" + i);
        }

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map(
                    "large-result-map",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        executionCount.incrementAndGet();
                        // Each item returns ~3KB string to push total well over 256KB
                        return item + "-" + "x".repeat(3000);
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            assertEquals(100, result.size());
            assertTrue(result.getResult(0).startsWith("item-0-"));
            assertTrue(result.getResult(99).startsWith("item-99-"));
            return "ok";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        assertEquals(events, result1.getHistoryEvents().size());
        var firstRunCount = executionCount.get();
        assertTrue(firstRunCount >= 100);

        // Replay — large result path: replayChildren=true, children replay from cache
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        if (nestingType == NestingType.FLAT) {
            // in FLAT mode, children will always be replayed
            assertEquals(firstRunCount * 2, executionCount.get(), "Map functions should re-execute on replay");
        } else {
            assertEquals(firstRunCount, executionCount.get(), "Map functions should not re-execute on replay");
        }
        assertEquals(events, result2.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 2", "NESTED, 8"})
    void testMapWithNullResults(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var items = List.of("a", "b", "c");
            var result = context.map(
                    "null-map",
                    items,
                    String.class,
                    (item, index, ctx) -> null,
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            assertEquals(3, result.size());
            for (int i = 0; i < result.size(); i++) {
                assertEquals(
                        MapResult.MapResultItem.Status.SUCCEEDED,
                        result.getItem(i).status());
                assertNull(result.getResult(i));
                assertNull(result.getError(i));
            }
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }

    // ---- 50-item map tests with waitForCallback ----

    @ParameterizedTest
    @CsvSource({"FLAT, 302", "NESTED, 402"})
    void testMap50ItemsWithWaitForCallback(NestingType nestingType, int events) {
        var itemCount = 50;
        var items = new ArrayList<Integer>();
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map(
                    "50-callbacks",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        return ctx.waitForCallback("approval-" + index, String.class, (callbackId, stepCtx) -> {});
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            assertEquals(itemCount, result.size());
            return String.valueOf(result.succeeded().size());
        });

        // First run — all items create callbacks and suspend
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete all 50 callbacks
        for (int i = 0; i < itemCount; i++) {
            var callbackId = runner.getCallbackId("approval-" + i + "-callback");
            assertNotNull(callbackId, "Callback ID should exist for approval-" + i);
            runner.completeCallback(callbackId, "\"result-" + i + "\"");
        }

        // Re-run — all callbacks resolved, execution completes
        result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("50", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 302", "NESTED, 402"})
    void testMap50ItemsWithWaitForCallback_maxConcurrency5(NestingType nestingType, int events) {
        var itemCount = 50;
        var items = new ArrayList<Integer>();
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = MapConfig.builder()
                    .maxConcurrency(5)
                    .nestingType(nestingType)
                    .build();
            var result = context.map(
                    "50-callbacks-limited",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        return ctx.waitForCallback("cb-" + index, String.class, (callbackId, stepCtx) -> {});
                    },
                    config);

            assertTrue(result.allSucceeded());
            return String.valueOf(result.succeeded().size());
        });

        // First run — suspends on callbacks
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete callbacks in batches, re-running between batches to let concurrency-limited items start
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

        result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("50", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 302", "NESTED, 402"})
    void testMap50ItemsWithWaitForCallback_partialFailure(NestingType nestingType, int events) {
        var itemCount = 50;
        var items = new ArrayList<Integer>();
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map(
                    "50-callbacks-partial-fail",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        return ctx.waitForCallback("approval-" + index, String.class, (callbackId, stepCtx) -> {});
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertEquals(itemCount, result.size());
            assertEquals(25, result.succeeded().size());
            assertEquals(25, result.failed().size());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionReason());

            return result.succeeded().size() + "/" + result.failed().size();
        });

        // First run — all items create callbacks and suspend
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete even-indexed callbacks, fail odd-indexed ones
        for (int i = 0; i < itemCount; i++) {
            var callbackId = runner.getCallbackId("approval-" + i + "-callback");
            assertNotNull(callbackId, "Callback ID should exist for approval-" + i);
            if (i % 2 == 0) {
                runner.completeCallback(callbackId, "\"ok-" + i + "\"");
            } else {
                runner.failCallback(
                        callbackId,
                        software.amazon.awssdk.services.lambda.model.ErrorObject.builder()
                                .errorType("Rejected")
                                .errorMessage("Item " + i + " rejected")
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
    void testMap50ItemsWithWaitForCallback_stepsBeforeAndAfterCallback(NestingType nestingType, int events) {
        var itemCount = 50;
        var items = new ArrayList<Integer>();
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map(
                    "50-callbacks-with-steps",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        var before = ctx.step("prepare-" + index, String.class, stepCtx -> "prepared-" + index);
                        var approval =
                                ctx.waitForCallback("approval-" + index, String.class, (callbackId, stepCtx) -> {});
                        return ctx.step(
                                "finalize-" + index, String.class, stepCtx -> before + ":" + approval + ":done");
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            return String.valueOf(result.succeeded().size());
        });

        // First run — items execute prepare step, create callbacks, suspend
        var result = runner.run("test");
        assertEquals(ExecutionStatus.PENDING, result.getStatus());

        // Complete all callbacks
        for (int i = 0; i < itemCount; i++) {
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

    // ---- 50-item map tests with waitForCondition ----

    @ParameterizedTest
    @CsvSource({"FLAT, 200", "NESTED, 300"})
    void testMap50ItemsWithWaitForCondition(NestingType nestingType, int events) {
        var itemCount = 50;
        var items = new ArrayList<Integer>();
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }
        var checkCounts = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map(
                    "50-conditions",
                    items,
                    Integer.class,
                    (item, index, ctx) -> {
                        var targetChecks = (index % 3) + 1; // 1, 2, or 3 checks to complete
                        var strategy = WaitStrategies.<Integer>fixedDelay(10, Duration.ofSeconds(1));
                        var wfcConfig = WaitForConditionConfig.<Integer>builder()
                                .waitStrategy(strategy)
                                .build();

                        return ctx.waitForCondition(
                                "poll-" + index,
                                Integer.class,
                                (state, stepCtx) -> {
                                    checkCounts.incrementAndGet();
                                    var next = (state == null ? 0 : state) + 1;
                                    return next >= targetChecks
                                            ? WaitForConditionResult.stopPolling(next)
                                            : WaitForConditionResult.continuePolling(next);
                                },
                                wfcConfig);
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            assertEquals(itemCount, result.size());

            var sum = 0;
            for (int i = 0; i < result.size(); i++) {
                sum += result.getResult(i);
            }
            return String.valueOf(sum);
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        // Each item completes after (index%3)+1 checks: 17 items need 1, 17 need 2, 16 need 3
        // Sum of results: 17*1 + 17*2 + 16*3 = 17 + 34 + 48 = 99
        assertEquals("99", result.getResult(String.class));
        assertTrue(checkCounts.get() >= itemCount, "Should have at least " + itemCount + " checks");
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 152", "NESTED, 252"})
    void testMap50ItemsWithWaitForCondition_someExceedMaxAttempts(NestingType nestingType, int events) {
        var itemCount = 50;
        var items = new ArrayList<Integer>();
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map(
                    "50-conditions-some-fail",
                    items,
                    Integer.class,
                    (item, index, ctx) -> {
                        // Odd items: maxAttempts=1 but need 2 checks → will fail
                        // Even items: maxAttempts=5, need 2 checks → will succeed
                        var maxAttempts = (index % 2 == 0) ? 5 : 1;
                        var strategy = WaitStrategies.<Integer>fixedDelay(maxAttempts, Duration.ofSeconds(1));
                        var wfcConfig = WaitForConditionConfig.<Integer>builder()
                                .waitStrategy(strategy)
                                .build();

                        return ctx.waitForCondition(
                                "poll-" + index,
                                Integer.class,
                                (state, stepCtx) -> {
                                    var next = (state == null ? 0 : state) + 1;
                                    return next >= 2
                                            ? WaitForConditionResult.stopPolling(next)
                                            : WaitForConditionResult.continuePolling(next);
                                },
                                wfcConfig);
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertEquals(itemCount, result.size());
            assertEquals(25, result.succeeded().size());
            assertEquals(25, result.failed().size());
            assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionReason());

            return result.succeeded().size() + "/" + result.failed().size();
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("25/25", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 102", "NESTED, 202"})
    void testMap50ItemsWithWaitForCondition_replay(NestingType nestingType, int events) {
        var itemCount = 50;
        var items = new ArrayList<Integer>();
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }
        var checkCounts = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map(
                    "50-conditions-replay",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        var strategy = WaitStrategies.<Integer>fixedDelay(5, Duration.ofSeconds(1));
                        var wfcConfig = WaitForConditionConfig.<Integer>builder()
                                .waitStrategy(strategy)
                                .build();

                        var polled = ctx.waitForCondition(
                                "poll-" + index,
                                Integer.class,
                                (state, stepCtx) -> {
                                    checkCounts.incrementAndGet();
                                    return WaitForConditionResult.stopPolling(1);
                                },
                                wfcConfig);

                        return String.valueOf(polled);
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            return "done";
        });

        var result1 = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result1.getStatus());
        var firstRunChecks = checkCounts.get();
        assertEquals(itemCount, firstRunChecks);
        assertEquals(events, result1.getHistoryEvents().size());

        // Replay — check functions should not re-execute
        var result2 = runner.run("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result2.getStatus());
        assertEquals(firstRunChecks, checkCounts.get(), "Check functions should not re-execute on replay");
        assertEquals(events, result2.getHistoryEvents().size());
    }

    // ---- 50-item map tests mixing waitForCallback and waitForCondition ----

    @ParameterizedTest
    @CsvSource({"FLAT, 202", "NESTED, 302"})
    void testMap50ItemsMixed_callbackAndCondition(NestingType nestingType, int events) {
        var itemCount = 50;
        var items = new ArrayList<Integer>();
        for (int i = 0; i < itemCount; i++) {
            items.add(i);
        }

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map(
                    "50-mixed",
                    items,
                    String.class,
                    (item, index, ctx) -> {
                        if (index % 2 == 0) {
                            // Even items: waitForCallback
                            return ctx.waitForCallback("cb-" + index, String.class, (callbackId, stepCtx) -> {});
                        } else {
                            // Odd items: waitForCondition
                            var strategy = WaitStrategies.<Integer>fixedDelay(5, Duration.ofSeconds(1));
                            var wfcConfig = WaitForConditionConfig.<Integer>builder()
                                    .waitStrategy(strategy)
                                    .build();

                            var polled = ctx.waitForCondition(
                                    "poll-" + index,
                                    Integer.class,
                                    (state, stepCtx) -> WaitForConditionResult.stopPolling(index),
                                    wfcConfig);

                            return "polled-" + polled;
                        }
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            assertEquals(itemCount, result.size());
            return String.valueOf(result.succeeded().size());
        });

        // First run — callback items suspend, condition items may complete
        var result = runner.run("test");

        // Complete all callback items (even-indexed)
        for (int i = 0; i < itemCount; i += 2) {
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
    @CsvSource({"FLAT, 24", "NESTED, 42"})
    void testMultipleMapAsyncInParallel(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var numbers = List.of(1, 2, 3);
            var letters = List.of("a", "b");
            var words = List.of("hello", "world", "foo", "bar");

            var numbersFuture = context.mapAsync(
                    "map-numbers",
                    numbers,
                    String.class,
                    (item, index, ctx) -> {
                        return ctx.step("double-" + index, String.class, stepCtx -> String.valueOf(item * 2));
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            var lettersFuture = context.mapAsync(
                    "map-letters",
                    letters,
                    String.class,
                    (item, index, ctx) -> {
                        return ctx.step("upper-" + index, String.class, stepCtx -> item.toUpperCase());
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            var wordsFuture = context.mapAsync(
                    "map-words",
                    words,
                    String.class,
                    (item, index, ctx) -> {
                        return ctx.step("reverse-" + index, String.class, stepCtx -> new StringBuilder(item)
                                .reverse()
                                .toString());
                    },
                    MapConfig.builder().nestingType(nestingType).build());

            var numbersResult = numbersFuture.get();
            var lettersResult = lettersFuture.get();
            var wordsResult = wordsFuture.get();

            assertTrue(numbersResult.allSucceeded());
            assertTrue(lettersResult.allSucceeded());
            assertTrue(wordsResult.allSucceeded());

            return String.join(",", numbersResult.results())
                    + "|" + String.join(",", lettersResult.results())
                    + "|" + String.join(",", wordsResult.results());
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("2,4,6|A,B|olleh,dlrow,oof,rab", result.getResult(String.class));
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 0", "NESTED, 0"})
    void testMapWithEmptyItems(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            List<String> items = List.of();
            var result = context.map(
                    "empty-map",
                    items,
                    String.class,
                    (item, index, ctx) -> item,
                    MapConfig.builder().nestingType(nestingType).build());

            assertTrue(result.allSucceeded());
            assertEquals(0, result.size());
            assertTrue(result.results().isEmpty());
            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }

    @ParameterizedTest
    @CsvSource({"FLAT, 0", "NESTED, 0"})
    void testAnyOfMapWithEmptyItems(NestingType nestingType, int events) {
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            List<String> items = List.of();
            var result = context.mapAsync(
                    "empty-map",
                    items,
                    String.class,
                    (item, index, ctx) -> item,
                    MapConfig.builder().nestingType(nestingType).build());

            DurableFuture.anyOf(result);

            return "done";
        });

        var result = runner.runUntilComplete("test");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(events, result.getHistoryEvents().size());
    }
}
