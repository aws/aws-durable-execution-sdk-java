// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import java.util.ArrayList;
import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.ParallelConfig;

/**
 * 8-12: Parallel with FLAT nesting executes branches in virtual contexts, omitting per-branch
 * ContextStarted/ContextSucceeded events
 *
 * <p>Two branches, each running a single step ("fa"/"fb"), max-concurrency=1, nesting {@link NestingType#FLAT}. With
 * FLAT nesting the branches use virtual contexts, so no ParallelBranch context events are checkpointed; each branch's
 * step is checkpointed directly under the parent Parallel context.
 */
public class ParallelFlat extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .nestingType(NestingType.FLAT)
                .build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("flat", config);
        try (parallel) {
            futures.add(parallel.branch(
                    "branch-0", String.class, branch -> branch.step("step-0", String.class, stepCtx -> "fa")));
            futures.add(parallel.branch(
                    "branch-1", String.class, branch -> branch.step("step-1", String.class, stepCtx -> "fb")));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }
}
