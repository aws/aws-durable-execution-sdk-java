// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.ParallelConfig;

/**
 * 8-24: A parallel branch can complete before later branches are registered.
 *
 * <p>The first branch is awaited while the parallel operation is still open. The second branch is registered only after
 * that result is available, proving that registration starts branches before the operation is sealed.
 */
public class ParallelEarlyStart extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder().maxConcurrency(1).build();
        ParallelDurableFuture parallel = context.parallel("early-start", config);
        DurableFuture<String> second;
        String firstResult;

        try (parallel) {
            var first = parallel.branch("first", String.class, branch -> "ready");
            firstResult = first.get();
            second = parallel.branch("second", String.class, branch -> firstResult + "-second");
        }

        return List.of(firstResult, second.get());
    }
}
