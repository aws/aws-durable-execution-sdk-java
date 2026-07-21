// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import java.util.ArrayList;
import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;

/**
 * 8-2: Parallel invoked with the branches-only form (no name argument)
 *
 * <p>DIVERGENCE NOTE: The Java SDK has no branches-only {@code parallel()} overload — {@code parallel(String name,
 * ...)} always requires a name. A name is supplied here ("branches-only") but it does not appear in the asserted
 * execution history, so behavior and result are identical to the named form. Each branch returns its constant directly
 * (no inner step), matching the requirement's step-free history.
 */
public class ParallelBranchesOnly extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(1).build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("branches-only", config);
        try (parallel) {
            futures.add(parallel.branch("branch-0", String.class, branch -> "alpha"));
            futures.add(parallel.branch("branch-1", String.class, branch -> "beta"));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }
}
