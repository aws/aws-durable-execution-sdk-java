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

/** 8-1: Parallel basic (two branches, each a single step, all succeed) */
public class ParallelBasic extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(1).build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("parallel", config);
        try (parallel) {
            futures.add(parallel.branch(
                    "branch-0", String.class, branch -> branch.step("step-0", String.class, stepCtx -> "task-1")));
            futures.add(parallel.branch(
                    "branch-1", String.class, branch -> branch.step("step-1", String.class, stepCtx -> "task-2")));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }
}
