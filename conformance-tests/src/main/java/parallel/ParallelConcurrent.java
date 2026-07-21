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
 * 8-11: Parallel executing branches concurrently returns results in branch index order
 *
 * <p>Three branches with max-concurrency=2, so up to two branches run concurrently and complete in a nondeterministic
 * order. Results are collected from the branch futures in registration (index) order, which the SDK guarantees
 * regardless of completion order.
 */
public class ParallelConcurrent extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(2).build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("concurrent", config);
        try (parallel) {
            futures.add(parallel.branch("branch-0", String.class, branch -> "r0"));
            futures.add(parallel.branch("branch-1", String.class, branch -> "r1"));
            futures.add(parallel.branch("branch-2", String.class, branch -> "r2"));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }
}
