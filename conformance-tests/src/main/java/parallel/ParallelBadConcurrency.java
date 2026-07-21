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
 * 8-19: Parallel with invalid max-concurrency raises a validation error
 *
 * <p>max-concurrency 0 is invalid; the SDK is expected to reject it and the execution fails.
 */
public class ParallelBadConcurrency extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(0).build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("bad-concurrency", config);
        try (parallel) {
            futures.add(parallel.branch("branch-0", String.class, branch -> "a"));
            futures.add(parallel.branch("branch-1", String.class, branch -> "b"));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }
}
