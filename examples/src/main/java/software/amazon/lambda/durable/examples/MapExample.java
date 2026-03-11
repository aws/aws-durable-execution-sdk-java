// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import java.util.List;
import software.amazon.lambda.durable.ConcurrencyConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelBranchConfig;
import software.amazon.lambda.durable.TypeToken;

/**
 * Simple example demonstrating basic step execution with the Durable Execution SDK.
 *
 * <p>This handler processes a greeting request through three sequential steps:
 *
 * <ol>
 *   <li>Create greeting message
 *   <li>Transform to uppercase
 *   <li>Add punctuation
 * </ol>
 */
public class MapExample extends DurableHandler<GreetingRequest, String> {

    @Override
    public String handleRequest(GreetingRequest input, DurableContext context) {
        var squared = context.mapAsync(
                "map example",
                List.of(1, 2, 3),
                (ctx, item, index) -> item * item,
                TypeToken.get(Integer.class),
                new ConcurrencyConfig(10, 2, 1));

        var parallel = context.parallelAsync("parallel example", new ConcurrencyConfig(10, 2, 1));
        var b1 = parallel.branch("branch1", TypeToken.get(String.class), ctx -> "hello", new ParallelBranchConfig());
        var b2 = parallel.branch("branch2", TypeToken.get(String.class), ctx -> "world", new ParallelBranchConfig());

        var result = parallel.get();
        return b1.get() + " " + b2.get();
    }
}
