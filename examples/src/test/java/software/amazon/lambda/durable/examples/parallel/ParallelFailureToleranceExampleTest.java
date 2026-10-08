// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.parallel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionEndInfo;
import software.amazon.lambda.durable.plugin.UserFunctionStartInfo;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ParallelFailureToleranceExampleTest {

    @Test
    void succeedsWhenFailuresAreWithinTolerance() {
        var handler = new ParallelFailureToleranceExample();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler);

        // 2 good services, 1 bad — toleratedFailureCount=1 so the parallel op still succeeds
        var input = new ParallelFailureToleranceExample.Input(List.of("svc-a", "bad-svc-b", "svc-c"), 1, null);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelFailureToleranceExample.Output.class);
        assertEquals(2, output.succeeded());
        assertEquals(1, output.failed());
    }

    @Test
    void succeedsWhenAllBranchesSucceed() {
        var handler = new ParallelFailureToleranceExample();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler);

        var input = new ParallelFailureToleranceExample.Input(List.of("svc-a", "svc-b", "svc-c"), 2, null);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelFailureToleranceExample.Output.class);
        assertEquals(3, output.succeeded());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failsWhenFailuresExceedTolerance(boolean holdHealthyBranch) throws Exception {
        var handler = new ParallelFailureToleranceExample();
        var probe = new CompletionProbe(holdHealthyBranch);
        var config = DurableConfig.builder().withPlugins(probe.newPlugin()).build();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler)
                .withDurableConfig(config);
        var caller = Executors.newSingleThreadExecutor();
        try {
            var input = new ParallelFailureToleranceExample.Input(List.of("svc-a", "bad-svc-b", "bad-svc-c"), 1, 2);
            var invocation = caller.submit(() -> runner.runUntilComplete(input));
            if (holdHealthyBranch) {
                await(probe.parallelStored);
                probe.releaseHealthy.countDown();
            }
            var result = invocation.get(5, TimeUnit.SECONDS);
            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
            var output = result.getResult(ParallelFailureToleranceExample.Output.class);
            assertEquals(2, output.failed());

            var operation = result.getOperation("call-services");
            assertEquals(OperationStatus.SUCCEEDED, operation.getStatus());
            var stored = new JacksonSerDes()
                    .deserialize(operation.getContextDetails().result(), TypeToken.get(ParallelResult.class));
            assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, stored.completionStatus());
            assertFalse(stored.completionStatus().isSucceeded());
            assertEquals(3, stored.size());
            assertEquals(
                    List.of(ParallelResult.Status.FAILED, ParallelResult.Status.FAILED),
                    stored.statuses().subList(1, 3));
            assertTrue(List.of(ParallelResult.Status.SUCCEEDED, ParallelResult.Status.SKIPPED)
                    .contains(stored.statuses().get(0)));
            assertEquals(output.succeeded(), stored.succeeded());
            assertEquals(output.failed(), stored.failed());
            assertEquals(1 - output.succeeded(), stored.skipped());
            if (holdHealthyBranch) assertEquals(0, output.succeeded());

            var completedCalls = result.getOperations().stream()
                    .filter(op -> op.getType() == OperationType.STEP && op.isCompleted())
                    .collect(Collectors.toMap(op -> op.getName(), op -> probe.completedCalls.get(op.getName())));
            assertEquals(1, completedCalls.get("invoke-bad-svc-b"));
            assertEquals(1, completedCalls.get("invoke-bad-svc-c"));
            var replay = runner.run(input);
            assertEquals(ExecutionStatus.SUCCEEDED, replay.getStatus());
            assertEquals(output, replay.getResult(ParallelFailureToleranceExample.Output.class));
            completedCalls.forEach((name, count) -> assertEquals(
                    count,
                    probe.completedCalls.get(name),
                    "Completed step bodies must not run again on replay: " + name));
        } finally {
            probe.releaseHealthy.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void failsWhenOnlyFailedBranchesExceedTolerance() {
        var handler = new ParallelFailureToleranceExample();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler);

        // Both failures must complete to exceed tolerance. A racing good branch need not finish before that point.
        var input = new ParallelFailureToleranceExample.Input(List.of("bad-svc-b", "bad-svc-c"), 1, 2);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelFailureToleranceExample.Output.class);
        assertEquals(2, output.failed());
        assertEquals(0, output.succeeded());
        var parallel = new JacksonSerDes()
                .deserialize(
                        result.getOperation("call-services").getContextDetails().result(),
                        TypeToken.get(ParallelResult.class));
        assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, parallel.completionStatus());
        assertEquals(2, parallel.failed());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled branch scheduling was not released");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class CompletionProbe {
        final boolean holdHealthy;
        final CountDownLatch healthyStarted = new CountDownLatch(1);
        final CountDownLatch releaseHealthy = new CountDownLatch(1);
        final CountDownLatch parallelStored = new CountDownLatch(1);
        final Map<String, Integer> completedCalls = new ConcurrentHashMap<>();

        CompletionProbe(boolean holdHealthy) {
            this.holdHealthy = holdHealthy;
        }

        DurableExecutionPlugin newPlugin() {
            return new DurableExecutionPlugin() {
                @Override
                public void onUserFunctionStart(UserFunctionStartInfo info) {
                    if (!holdHealthy || !"STEP".equals(info.type())) return;
                    if ("invoke-svc-a".equals(info.name())) {
                        healthyStarted.countDown();
                        await(releaseHealthy);
                    } else if (info.name().startsWith("invoke-bad-")) await(healthyStarted);
                }

                @Override
                public void onUserFunctionEnd(UserFunctionEndInfo info) {
                    if ("STEP".equals(info.type())) completedCalls.merge(info.name(), 1, Integer::sum);
                }

                @Override
                public void onOperationEnd(OperationEndInfo info) {
                    if ("call-services".equals(info.name())) parallelStored.countDown();
                }
            };
        }
    }
}
