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
 * 8-3: Parallel invoked with named-branch objects (a name plus a function)
 *
 * <p>In the Java SDK every branch is created via {@code branch(name, type, func)}, so each branch is inherently named.
 * The branch names "first" and "second" are supplied; each function returns its constant directly.
 */
public class ParallelNamedBranches extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(1).build();
        var futures = new ArrayList<DurableFuture<String>>();
        ParallelDurableFuture parallel = context.parallel("named", config);
        try (parallel) {
            futures.add(parallel.branch("first", String.class, branch -> "one"));
            futures.add(parallel.branch("second", String.class, branch -> "two"));
        }
        return futures.stream().map(DurableFuture::get).toList();
    }
}
