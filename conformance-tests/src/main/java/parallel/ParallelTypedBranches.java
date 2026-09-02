// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.ParallelConfig;

/**
 * 8-23: Parallel exposes independently typed handles for heterogeneous branch results.
 *
 * <p>Each branch declares its own concrete result type, including a parameterized map, and the values are retrieved
 * from those typed handles after the parallel operation is sealed.
 */
public class ParallelTypedBranches extends DurableHandler<Object, List<Object>> {

    @Override
    public List<Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(1).build();
        ParallelDurableFuture parallel = context.parallel("typed-branches", config);
        DurableFuture<String> inventory;
        DurableFuture<Integer> payment;
        DurableFuture<Map<String, String>> quote;

        try (parallel) {
            inventory = parallel.branch("inventory", String.class, branch -> "reserved");
            payment = parallel.branch("payment", Integer.class, branch -> 200);
            quote = parallel.branch(
                    "quote", new TypeToken<Map<String, String>>() {}, branch -> Map.of("currency", "USD"));
        }

        return List.of(inventory.get(), payment.get(), quote.get());
    }
}
