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
 * 8-18: Parallel with combined completion config (min-successful + tolerated-failure-count)
 *
 * <p>min-successful=3 and tolerated-failure-count=1 together. Branches 0 and 1 fail; the failure tolerance is exceeded
 * (2 > 1) before the min-successful target could be met, so the operation stops and branches 2 and 3 never start.
 * Completion reason is FAILURE_TOLERANCE_EXCEEDED. Combined config uses the CompletionConfig record constructor.
 */
public class ParallelCombinedConfig extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .completionConfig(new CompletionConfig(3, 1, null))
                .build();
        ParallelDurableFuture parallel = context.parallel("combined", config);
        try (parallel) {
            parallel.branch("branch-0", String.class, branch -> {
                throw new RuntimeException("branch 0 failed");
            });
            parallel.branch("branch-1", String.class, branch -> {
                throw new RuntimeException("branch 1 failed");
            });
            parallel.branch("branch-2", String.class, branch -> "ok2");
            parallel.branch("branch-3", String.class, branch -> "ok3");
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
