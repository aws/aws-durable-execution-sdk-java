// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;

/**
 * 8-14: Parallel replay skips succeeded branches across a wait suspension
 *
 * <p>Branch 0 runs a step and succeeds; branch 1 waits 2s then returns. With max-concurrency 1 the first invocation
 * completes branch 0 and suspends on branch 1's wait; on replay branch 0 is skipped and reconstructed.
 */
public class ParallelReplaySkipsSucceeded extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(1).build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("replay", config);
        try (parallel) {
            futures.add(parallel.branch(
                    "branch-0", String.class, branch -> branch.step("step-0", String.class, stepCtx -> "b0")));
            futures.add(parallel.branch("branch-1", String.class, branch -> {
                branch.wait(null, Duration.ofSeconds(2));
                return "b1";
            }));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }
}
