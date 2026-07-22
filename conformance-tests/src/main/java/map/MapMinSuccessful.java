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
 * 9-7: Map min-successful early completion. After items 0 and 1 succeed the threshold (2) is reached and items 2 and 3
 * are never started. totalCount is projected as succeeded+failed (started items) = 2.
 */
public class MapMinSuccessful extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.minSuccessful(2))
                .build();
        MapResult<String> result = context.map(
                "min-successful", List.of("s0", "s1", "s2", "s3"), String.class, (item, index, ctx) -> item, config);

        var out = new LinkedHashMap<String, Object>();
        out.put("completionReason", result.completionReason().name());
        out.put("successCount", result.succeeded().size());
        out.put("totalCount", result.succeeded().size() + result.failed().size());
        return out;
    }
}
