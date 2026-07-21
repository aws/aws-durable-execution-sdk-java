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
 * 8-20: Parallel ParallelResult accessors
 *
 * <p>One failure within tolerance so all branches run. Projects hasFailure/successCount/failureCount/errorCount from
 * the ParallelResult counts. Java's ParallelResult exposes counts (succeeded/failed) rather than an errors list, so
 * errorCount is derived from failed().
 */
public class ParallelAccessors extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = ParallelConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.toleratedFailureCount(1))
                .build();
        ParallelDurableFuture parallel = context.parallel("accessors", config);
        try (parallel) {
            parallel.branch("branch-0", String.class, branch -> "ok0");
            parallel.branch("branch-1", String.class, branch -> {
                throw new RuntimeException("branch 1 failed");
            });
            parallel.branch("branch-2", String.class, branch -> "ok2");
        }
        ParallelResult result = parallel.get();

        var out = new LinkedHashMap<String, Object>();
        out.put("hasFailure", result.failed() > 0);
        out.put("successCount", result.succeeded());
        out.put("failureCount", result.failed());
        out.put("errorCount", result.failed());
        return out;
    }
}
