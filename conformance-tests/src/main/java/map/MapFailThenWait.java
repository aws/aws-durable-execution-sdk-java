// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/**
 * 9-18: Suspension after a map that completed with a failure (replay skips the completed map).
 *
 * <p>With tolerated-failure-count=1 both items run (item 1 fails, recorded). The map does not rethrow; a durable wait
 * then suspends the execution. On replay the completed map (including the failed iteration) is skipped.
 */
public class MapFailThenWait extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.toleratedFailureCount(1))
                .build();
        MapResult<String> result = context.map(
                "fail-then-wait",
                List.of("ok", "fail"),
                String.class,
                (item, index, ctx) -> {
                    if (item.equals("fail")) {
                        throw new RuntimeException("item failed");
                    }
                    return item;
                },
                config);
        context.wait(null, Duration.ofSeconds(1));

        var out = new LinkedHashMap<String, Object>();
        out.put("completionReason", result.completionReason().name());
        out.put("status", result.allSucceeded() ? "SUCCEEDED" : "FAILED");
        out.put("successCount", result.succeeded().size());
        out.put("failureCount", result.failed().size());
        out.put("totalCount", result.succeeded().size() + result.failed().size());
        return out;
    }
}
