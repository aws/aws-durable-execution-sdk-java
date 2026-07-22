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
 * 9-10: Map tolerated-failure-percentage exceeded (stops early).
 *
 * <p>Four items, tolerated-failure-percentage=25%. Java's {@code CompletionConfig.toleratedFailurePercentage} uses a
 * 0.0-1.0 scale, so 25% is expressed as {@code 0.25} (the JS/Python SDKs use 25). Items 0 and 1 fail (2/4 = 50% exceeds
 * 25%), so items 2 and 3 are never started.
 */
public class MapToleratedPct extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.toleratedFailurePercentage(0.25))
                .build();
        MapResult<String> result = context.map(
                "tolerated-pct",
                List.of("f0", "f1", "never", "never"),
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
