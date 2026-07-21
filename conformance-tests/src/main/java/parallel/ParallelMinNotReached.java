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
 * 8-17: Parallel min-successful not reached (all branches run)
 *
 * <p>Branch 0 succeeds, branch 1 fails, branch 2 succeeds; min-successful=3 is never reached and there is no failure
 * tolerance, so all branches run. Completion reason is ALL_COMPLETED and status is FAILED.
 */
public class ParallelMinNotReached extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.minSuccessful(3))
                .build();
        ParallelDurableFuture parallel = context.parallel("min-not-reached", config);
        try (parallel) {
            parallel.branch("branch-0", String.class, branch -> "ok0");
            parallel.branch("branch-1", String.class, branch -> {
                throw new RuntimeException("branch 1 failed");
            });
            parallel.branch("branch-2", String.class, branch -> "ok2");
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
