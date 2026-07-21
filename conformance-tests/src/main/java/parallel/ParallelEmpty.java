// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;

/**
 * 8-5: Parallel invoked with an empty branches list completes immediately with no branches
 *
 * <p>No branches are registered; the parallel context is opened and immediately closed (join). The batch completes with
 * ALL_COMPLETED and zero branches, and the handler returns an empty list.
 */
public class ParallelEmpty extends DurableHandler<Object, List<Object>> {

    @Override
    public List<Object> handleRequest(Object input, DurableContext context) {
        ParallelDurableFuture parallel = context.parallel("empty");
        try (parallel) {
            // no branches registered
        }
        parallel.get();
        return List.of();
    }
}
