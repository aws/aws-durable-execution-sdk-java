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
 * 9-9: Map tolerated-failure-count exceeded (stops early). Items 0 and 1 fail (failure count 2 exceeds the tolerance of
 * 1), so item 2 is never started.
 */
public class MapToleratedExceeded extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.toleratedFailureCount(1))
                .build();
        MapResult<String> result = context.map(
                "tolerated-exceeded",
                List.of("f0", "f1", "never"),
                String.class,
                (item, index, ctx) -> {
                    if (!item.equals("never")) {
                        throw new RuntimeException("item failed");
                    }
                    return item;
                },
                config);

        var out = new LinkedHashMap<String, Object>();
        out.put("completionReason", result.completionReason().name());
        out.put("successCount", result.succeeded().size());
        out.put("failureCount", result.failed().size());
        out.put("totalCount", result.succeeded().size() + result.failed().size());
        return out;
    }
}
