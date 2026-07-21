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
 * 8-16: Parallel where all branches fail (within tolerance, ALL_COMPLETED)
 *
 * <p>Three branches all fail; tolerated-failure-count=3 accommodates them so all run and none is skipped. Completion
 * reason is ALL_COMPLETED but status is FAILED (no successes).
 */
public class ParallelAllFail extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.toleratedFailureCount(3))
                .build();
        ParallelDurableFuture parallel = context.parallel("all-fail", config);
        try (parallel) {
            parallel.branch("branch-0", String.class, branch -> {
                throw new RuntimeException("branch 0 failed");
            });
            parallel.branch("branch-1", String.class, branch -> {
                throw new RuntimeException("branch 1 failed");
            });
            parallel.branch("branch-2", String.class, branch -> {
                throw new RuntimeException("branch 2 failed");
            });
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
