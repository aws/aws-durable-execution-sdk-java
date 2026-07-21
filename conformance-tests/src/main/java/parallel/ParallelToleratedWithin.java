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
 * 8-9: Parallel with a tolerated-failure-count tolerating one failure completes all branches
 *
 * <p>Three branches, tolerated-failure-count=1, max-concurrency=1. Branch 1 fails but the failure count (1) does not
 * exceed the tolerance (1), so all branches complete (ALL_COMPLETED). {@code status} is FAILED because at least one
 * branch failed (derived from failed &gt; 0).
 */
public class ParallelToleratedWithin extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.toleratedFailureCount(1))
                .build();
        ParallelDurableFuture parallel = context.parallel("tolerated", config);
        try (parallel) {
            parallel.branch("branch-0", String.class, branch -> "s0");
            parallel.branch("branch-1", String.class, branch -> {
                throw new RuntimeException("branch 1 failed");
            });
            parallel.branch("branch-2", String.class, branch -> "s2");
        }
        ParallelResult result = parallel.get();

        var out = new LinkedHashMap<String, Object>();
        out.put("completionReason", result.completionStatus().name());
        out.put("status", result.failed() > 0 ? "FAILED" : "SUCCEEDED");
        out.put("successCount", result.succeeded());
        out.put("failureCount", result.failed());
        out.put("totalCount", result.succeeded() + result.failed());
        return out;
    }
}
