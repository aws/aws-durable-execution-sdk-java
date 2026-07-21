// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package parallel;

import java.util.LinkedHashMap;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.model.ParallelResult;

/**
 * 8-10: Parallel stops early once the failure count exceeds the tolerated-failure-count
 *
 * <p>Three branches, tolerated-failure-count=1, max-concurrency=1. Branch 0 fails (count 1, within tolerance), branch 1
 * fails (count 2, exceeding tolerance), so the operation stops and branch 2 is never started
 * (FAILURE_TOLERANCE_EXCEEDED). {@code totalCount} is projected as succeeded+failed (2 started branches).
 */
public class ParallelToleratedExceeded extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.toleratedFailureCount(1))
                .build();
        ParallelDurableFuture parallel = context.parallel("tolerated-exceeded", config);
        try (parallel) {
            parallel.branch("branch-0", String.class, branch -> {
                throw new RuntimeException("branch 0 failed");
            });
            parallel.branch("branch-1", String.class, branch -> {
                throw new RuntimeException("branch 1 failed");
            });
            parallel.branch("branch-2", String.class, branch -> "never");
        }
        ParallelResult result = parallel.get();

        var out = new LinkedHashMap<String, Object>();
        out.put("completionReason", result.completionStatus().name());
        out.put("successCount", result.succeeded());
        out.put("failureCount", result.failed());
        out.put("totalCount", result.succeeded() + result.failed());
        return out;
    }
}
