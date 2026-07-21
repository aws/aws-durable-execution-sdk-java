// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;

/**
 * 8-4: Parallel whose branches return different types (string, number, object)
 *
 * <p>Each branch declares its own result type token (String, Integer, Map) so every branch result is serialized and
 * deserialized with its own type preserved. Results are collected in branch index order.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ParallelHeterogeneous extends DurableHandler<Object, List<Object>> {

    @Override
    public List<Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(1).build();
        ParallelDurableFuture parallel = context.parallel("hetero", config);
        DurableFuture<String> f0;
        DurableFuture<Integer> f1;
        DurableFuture<Map> f2;
        try (parallel) {
            f0 = parallel.branch("branch-0", String.class, branch -> "hello");
            f1 = parallel.branch("branch-1", Integer.class, branch -> 42);
            f2 = parallel.branch("branch-2", Map.class, branch -> Map.of("k", "v"));
        }
        return List.of(f0.get(), f1.get(), f2.get());
    }
}
