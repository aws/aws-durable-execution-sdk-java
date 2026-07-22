// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/**
 * 9-8: Map tolerated-failure-count within tolerance (all items complete). One failure does not exceed the tolerance of
 * 1, so all items run; status is FAILED because at least one item failed.
 */
public class MapToleratedWithin extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.toleratedFailureCount(1))
                .build();
        MapResult<String> result = context.map(
                "tolerated",
                List.of("s0", "fail", "s2"),
                String.class,
                (item, index, ctx) -> {
                    if (item.equals("fail")) {
                        throw new RuntimeException("item failed");
                    }
                    return item;
                },
                config);

        var out = new LinkedHashMap<String, Object>();
        out.put("completionReason", result.completionReason().name());
        out.put("status", result.allSucceeded() ? "SUCCEEDED" : "FAILED");
        out.put("successCount", result.succeeded().size());
        out.put("failureCount", result.failed().size());
        out.put("totalCount", result.succeeded().size() + result.failed().size());
        return out;
    }
}
