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
 * 8-21: Nested parallel (a parallel operation inside a parallel branch)
 *
 * <p>The outer parallel has a single branch that itself runs an inner parallel of two steps ("i1", "i2") and returns
 * the inner ordered results. The outer result is therefore a single-element list containing the inner list.
 */
public class ParallelNested extends DurableHandler<Object, Object> {

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Object handleRequest(Object input, DurableContext context) {
        var outerConfig = ParallelConfig.builder().maxConcurrency(1).build();
        ParallelDurableFuture outer = context.parallel("outer", outerConfig);
        DurableFuture<List> branchAFuture;
        try (outer) {
            branchAFuture = outer.branch("branchA", List.class, branch -> {
                var innerConfig = ParallelConfig.builder().maxConcurrency(1).build();
                var innerFutures = new ArrayList<DurableFuture<String>>();
                ParallelDurableFuture inner = branch.parallel("inner", innerConfig);
                try (inner) {
                    innerFutures.add(
                            inner.branch("inner-0", String.class, c2 -> c2.step("step-0", String.class, sc -> "i1")));
                    innerFutures.add(
                            inner.branch("inner-1", String.class, c2 -> c2.step("step-1", String.class, sc -> "i2")));
                }
                return (List) innerFutures.stream().map(DurableFuture::get).toList();
            });
        }
        return List.of(branchAFuture.get());
    }
}
