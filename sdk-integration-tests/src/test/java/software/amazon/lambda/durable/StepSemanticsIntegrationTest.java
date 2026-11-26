// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class StepSemanticsIntegrationTest {

    @Test
    void testAtLeastOnceCompletesSuccessfully() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "my-step",
                        String.class,
                        stepCtx -> {
                            executionCount.incrementAndGet();
                            return "result";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_LEAST_ONCE)
                                .build()));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testAtMostOnceCompletesSuccessfully() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "my-step",
                        String.class,
                        stepCtx -> {
                            executionCount.incrementAndGet();
                            return "result";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE)
                                .build()));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testAtMostOnceNoRetryFailsImmediately() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step(
                        "my-step",
                        String.class,
                        stepCtx -> {
                            executionCount.incrementAndGet();
                            throw new RuntimeException("Always fails");
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE)
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build()));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testDefaultSemanticsPerRetryIsAtLeastOnce() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, ctx) -> ctx.step("my-step", String.class, stepCtx -> {
                    executionCount.incrementAndGet();
                    return "result";
                }));

        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, executionCount.get());
    }

    @Test
    void testAtLeastOnceReExecutesAfterCheckpointLoss() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "step",
                        String.class,
                        stepCtx -> {
                            var count = executionCount.incrementAndGet();
                            return "Executed " + count + " times";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_LEAST_ONCE)
                                .build()));

        runner.run("test");
        assertEquals(1, executionCount.get());

        runner.simulateFireAndForgetCheckpointLoss("step");

        var result = runner.run("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, executionCount.get());
    }

    @Test
    void testAtLeastOnceReExecutesAfterCheckpointFailure() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "step",
                        String.class,
                        stepCtx -> {
                            var count = executionCount.incrementAndGet();
                            return "Executed " + count + " times";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_LEAST_ONCE)
                                .build()));

        runner.run("test");
        assertEquals(1, executionCount.get());

        runner.resetCheckpointToStarted("step");
        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, executionCount.get());
    }

    @Test
    void testAtMostOnceRetriesAfterInterruption() {
        var executionCount = new AtomicInteger(0);

        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "step",
                        String.class,
                        stepCtx -> {
                            executionCount.incrementAndGet();
                            return "result";
                        },
                        StepConfig.builder()
                                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE)
                                .build()));

        runner.run("test");
        assertEquals(1, executionCount.get());

        runner.resetCheckpointToStarted("step");

        var result = runner.runUntilComplete("test");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, executionCount.get());
    }

    @SuppressWarnings("deprecation")
    static Stream<Arguments> deprecatedNames() {
        return Stream.of(
                Arguments.of(StepSemantics.AT_LEAST_ONCE_PER_RETRY, StepSemantics.AT_LEAST_ONCE),
                Arguments.of(StepSemantics.AT_MOST_ONCE_PER_RETRY, StepSemantics.AT_MOST_ONCE));
    }

    @ParameterizedTest
    @MethodSource("deprecatedNames")
    void deprecatedNameBehavesLikeNewName(StepSemantics deprecated, StepSemantics current) {
        assertEquals(outcomeAfterInterruption(current), outcomeAfterInterruption(deprecated));
    }

    @Test
    void interruptionOutcomeDiffersBetweenAtLeastOnceAndAtMostOnce() {
        assertEquals(List.of(ExecutionStatus.SUCCEEDED, 2), outcomeAfterInterruption(StepSemantics.AT_LEAST_ONCE));
        assertEquals(List.of(ExecutionStatus.FAILED, 1), outcomeAfterInterruption(StepSemantics.AT_MOST_ONCE));
    }

    @Test
    void defaultStepSemanticsAppliesToStepWithoutSemantics() {
        var outcome =
                outcomeAfterInterruption(atMostOnceByDefault(), noRetry(), (context, step) -> step.apply(context));

        assertEquals(List.of(ExecutionStatus.FAILED, 1), outcome);
    }

    @Test
    void stepSemanticsWinsOverDefaultStepSemantics() {
        var outcome = outcomeAfterInterruption(
                atMostOnceByDefault(),
                noRetry().semanticsPerRetry(StepSemantics.AT_LEAST_ONCE),
                (context, step) -> step.apply(context));

        assertEquals(List.of(ExecutionStatus.SUCCEEDED, 2), outcome);
    }

    @Test
    void defaultStepSemanticsAppliesInChildContext() {
        var outcome = outcomeAfterInterruption(
                atMostOnceByDefault(),
                noRetry(),
                (context, step) -> context.runInChildContext("child", String.class, step::apply),
                "child");

        assertEquals(List.of(ExecutionStatus.FAILED, 1), outcome);
    }

    @Test
    void defaultStepSemanticsAppliesInMapItem() {
        var outcome = outcomeAfterInterruption(
                atMostOnceByDefault(),
                noRetry(),
                (context, step) -> {
                    var result = context.map(
                            "map", List.of("item"), String.class, (item, index, child) -> step.apply(child));
                    // A failed item does not fail the map, so the handler reports it.
                    if (result.getError(0) != null) throw new IllegalStateException("item failed");
                    return result.getResult(0);
                },
                "map",
                "map-iteration-0");

        assertEquals(List.of(ExecutionStatus.FAILED, 1), outcome);
    }

    private static DurableConfig atMostOnceByDefault() {
        return DurableConfig.builder()
                .withDefaultStepSemantics(StepSemantics.AT_MOST_ONCE)
                .build();
    }

    private static StepConfig.Builder noRetry() {
        return StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY);
    }

    private static List<Object> outcomeAfterInterruption(StepSemantics semantics) {
        return outcomeAfterInterruption(
                null, noRetry().semanticsPerRetry(semantics), (context, step) -> step.apply(context));
    }

    /**
     * Runs a step named "step" without retries, marks it as interrupted, and returns the final status and the run
     * count. The handler runs the step through {@code around}, for example inside a child context. The completed
     * contexts around the step are marked as interrupted too, so that the replay reaches the step.
     */
    private static List<Object> outcomeAfterInterruption(
            DurableConfig config,
            StepConfig.Builder stepConfig,
            BiFunction<DurableContext, Function<DurableContext, String>, String> around,
            String... interruptedParents) {
        var executionCount = new AtomicInteger(0);
        Function<DurableContext, String> step = context -> context.step(
                "step",
                String.class,
                stepCtx -> {
                    executionCount.incrementAndGet();
                    return "result";
                },
                stepConfig.build());
        var runner =
                LocalDurableTestRunner.create(String.class, (input, context) -> around.apply(context, step), config);

        runner.run("test");
        for (var parent : interruptedParents) runner.resetCheckpointToStarted(parent);
        runner.resetCheckpointToStarted("step");
        var result = runner.runUntilComplete("test");

        return List.of(result.getStatus(), executionCount.get());
    }
}
