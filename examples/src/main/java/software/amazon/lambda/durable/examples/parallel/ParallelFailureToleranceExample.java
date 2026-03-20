// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.parallel;

import java.util.ArrayList;
import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelConfig;
import software.amazon.lambda.durable.StepConfig;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.retry.RetryStrategies;

/**
 * Example demonstrating parallel execution with failure tolerance.
 *
 * <p>When {@code toleratedFailureCount} is set, the parallel operation completes successfully even if some branches
 * fail — as long as the number of failures does not exceed the threshold. Failed branches produce {@code null} results
 * that callers can filter out.
 *
 * <p>Use this pattern when partial success is acceptable, for example: sending notifications to multiple channels where
 * some channels may be unavailable.
 */
public class ParallelFailureToleranceExample
        extends DurableHandler<ParallelFailureToleranceExample.Input, ParallelFailureToleranceExample.Output> {

    public record Input(List<String> services, int toleratedFailures, int minSuccessful) {}

    public record Output(int succeeded, int failed) {}

    @Override
    public Output handleRequest(Input input, DurableContext context) {
        var logger = context.getLogger();
        logger.info("Starting parallel execution with toleratedFailureCount={}", input.toleratedFailures());

        var config = ParallelConfig.builder()
                .minSuccessful(input.minSuccessful())
                .toleratedFailureCount(input.toleratedFailures())
                .build();

        var futures = new ArrayList<DurableFuture<String>>(input.services().size());
        var parallel = context.parallel("call-services", config);

        try (parallel) {
            for (var service : input.services()) {
                var future = parallel.branch("call-" + service, String.class, branchCtx -> {
                    return branchCtx.step(
                            "invoke-" + service,
                            String.class,
                            stepCtx -> {
                                if (service.startsWith("bad-")) {
                                    throw new RuntimeException("Service unavailable: " + service);
                                }
                                return "ok:" + service;
                            },
                            StepConfig.builder()
                                    .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                    .build());
                });
                futures.add(future);
            }
        }

        ParallelResult parallelResult = parallel.get();
        logger.info(
                "Parallel complete: succeeded={}, failed={}, status={}",
                parallelResult.getSucceededBranches(),
                parallelResult.getFailedBranches(),
                parallelResult.getCompletionStatus().isSucceeded() ? "succeeded" : "failed");

        var succeeded = parallelResult.getSucceededBranches();
        var failed = parallelResult.getFailedBranches();

        logger.info("Completed: {} succeeded, {} failed", succeeded, failed);
        return new Output(succeeded, failed);
    }
}
