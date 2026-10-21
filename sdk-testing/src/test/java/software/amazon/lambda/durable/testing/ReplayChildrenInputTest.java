// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationInfo;

class ReplayChildrenInputTest {
    private static final String LEFT = "a".repeat(140_000);
    private static final String RIGHT = "b".repeat(140_000);

    @Test
    void completedContextChildrenAreAbsentFromRealResumeInputButRemainInspectable() {
        var starts = new CopyOnWriteArrayList<InvocationInfo>();
        var steps = new AtomicInteger();
        var bodies = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var saved = context.runInChildContext("saved-child", String.class, child -> {
                        bodies.incrementAndGet();
                        return child.step("saved-step", String.class, step -> {
                            steps.incrementAndGet();
                            return "saved";
                        });
                    });
                    context.wait("pause", Duration.ofSeconds(1));
                    return saved;
                },
                config(starts));

        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.advanceTime();
        var result = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("saved", result.getResult(String.class));
        assertEquals(2, starts.size());
        assertTrue(names(starts.get(1)).containsAll(List.of("saved-child", "pause")));
        assertFalse(names(starts.get(1)).contains("saved-step"));
        assertEquals(1, steps.get());
        assertEquals(1, bodies.get());
        assertEquals("saved", runner.getOperation("saved-step").getStepResult(String.class));
    }

    @Test
    void largeChildResultRetainsItsStepsAndRebuildsWithoutRepeatingThem() {
        var starts = new CopyOnWriteArrayList<InvocationInfo>();
        var steps = new AtomicInteger();
        var bodies = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var saved = context.runInChildContext("retained", String.class, child -> {
                        bodies.incrementAndGet();
                        return largeChild(child, steps);
                    });
                    context.wait("pause", Duration.ofSeconds(1));
                    return saved.length(); // Keep the root execution result small.
                },
                config(starts));

        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.advanceTime();
        var result = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(LEFT.length() + RIGHT.length(), result.getResult(Integer.class));
        assertEquals(2, starts.size());
        assertTrue(names(starts.get(1)).containsAll(List.of("retained", "left", "right", "pause")));
        assertEquals(2, bodies.get());
        assertEquals(2, steps.get());
    }

    @Test
    void completedOuterContextPrunesEvenAnInnerContextThatRetainsChildren() {
        var starts = new CopyOnWriteArrayList<InvocationInfo>();
        var steps = new AtomicInteger();
        var bodies = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var length = context.runInChildContext("outer", Integer.class, outer -> {
                        bodies.incrementAndGet();
                        return outer.runInChildContext("inner", String.class, inner -> largeChild(inner, steps))
                                .length();
                    });
                    context.wait("pause", Duration.ofSeconds(1));
                    return length;
                },
                config(starts));

        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        runner.advanceTime();
        var result = runner.run("input");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(LEFT.length() + RIGHT.length(), result.getResult(Integer.class));
        assertEquals(2, starts.size());
        var delivered = names(starts.get(1));
        assertTrue(delivered.containsAll(List.of("outer", "pause")));
        for (var omitted : List.of("inner", "left", "right")) assertFalse(delivered.contains(omitted), omitted);
        assertEquals(1, bodies.get());
        assertEquals(2, steps.get());
        assertEquals(LEFT, runner.getOperation("left").getStepResult(String.class));
    }

    private static String largeChild(DurableContext child, AtomicInteger calls) {
        // Each step remains below the service payload limit; only the combined context result exceeds it.
        var left = child.step("left", String.class, step -> {
            calls.incrementAndGet();
            return LEFT;
        });
        var right = child.step("right", String.class, step -> {
            calls.incrementAndGet();
            return RIGHT;
        });
        return left + right;
    }

    private static DurableConfig config(List<InvocationInfo> starts) {
        return DurableConfig.builder()
                .withPlugins(ignored -> new DurableExecutionPlugin() {
                    public void onInvocationStart(InvocationInfo info) {
                        starts.add(info);
                    }
                })
                .build();
    }

    private static List<String> names(InvocationInfo info) {
        return info.operations().values().stream()
                .map(operation -> operation.name())
                .toList();
    }
}
