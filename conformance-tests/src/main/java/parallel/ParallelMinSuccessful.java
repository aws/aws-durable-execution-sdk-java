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
 * 8-8: Parallel with a min-successful completion config stops early once enough branches succeed
 *
 * <p>Four branches, min-successful=2, max-concurrency=1. After branches 0 and 1 succeed the threshold is reached and
 * branches 2 and 3 are never started. {@code totalCount} is projected as succeeded+failed (started branches), which is
 * 2 here — the Java {@code ParallelResult.size()} would instead include the SKIPPED branches.
 */
public class ParallelMinSuccessful extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.minSuccessful(2))
                .build();
        ParallelDurableFuture parallel = context.parallel("min-successful", config);
        try (parallel) {
            parallel.branch("branch-0", String.class, branch -> "v0");
            parallel.branch("branch-1", String.class, branch -> "v1");
            parallel.branch("branch-2", String.class, branch -> "v2");
            parallel.branch("branch-3", String.class, branch -> "v3");
        }
        ParallelResult result = parallel.get();

        var out = new LinkedHashMap<String, Object>();
        out.put("completionReason", result.completionStatus().name());
        out.put("successCount", result.succeeded());
        out.put("totalCount", result.succeeded() + result.failed());
        return out;
    }
}
