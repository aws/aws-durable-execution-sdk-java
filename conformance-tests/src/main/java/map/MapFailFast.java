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
 * 9-5: Map fail-fast via tolerated-failure-count=0 stops after the first item failure.
 *
 * <p>Fail-fast is configured explicitly via {@code CompletionConfig.allSuccessful()} (toleratedFailureCount=0). Item 0
 * returns "ok", item 1 throws, item 2 is never started. The handler projects the batch summary; status is FAILED
 * because a failure occurred.
 */
public class MapFailFast extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .completionConfig(CompletionConfig.allSuccessful())
                .build();
        MapResult<String> result = context.map(
                "failfast",
                List.of("ok", "fail", "never"),
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
