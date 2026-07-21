// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelConfig;

/**
 * 8-7: Parallel where the handler asks the batch result to rethrow, propagating a branch failure so the execution fails
 *
 * <p>DIVERGENCE NOTES:
 *
 * <ul>
 *   <li>Fail-fast is configured explicitly via {@code CompletionConfig.allSuccessful()} (toleratedFailureCount=0),
 *       matching the reframed requirement; Java's default is {@code allCompleted()}, so it must be requested
 *       explicitly.
 *   <li>The Java {@code ParallelResult} record has no "throw-if-error" method. To propagate the branch failure, the
 *       handler calls {@code get()} on the failed branch's {@link DurableFuture}, which rethrows the branch exception.
 *       The uncaught exception fails the execution.
 * </ul>
 */
public class ParallelThrowIfError extends DurableHandler<Object, String> {

    @Override
    public String handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.allSuccessful())
                .build();
        ParallelDurableFuture parallel = context.parallel("throwing", config);
        DurableFuture<String> branch0;
        try (parallel) {
            branch0 = parallel.branch("branch-0", String.class, branch -> {
                throw new RuntimeException("branch 0 failed");
            });
            parallel.branch("branch-1", String.class, branch -> "never");
        }
        parallel.get();
        // Rethrow the first branch failure (Java has no batch-level throw-if-error); propagates uncaught.
        return branch0.get();
    }
}
