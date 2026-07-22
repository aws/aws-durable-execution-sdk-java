// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/**
 * 9-16: Map with a large aggregate result (exceeds the checkpoint size threshold).
 *
 * <p>Four items each returning ~70KB (~280KB aggregate) force the SDK to checkpoint the Map result with a stripped
 * payload / replay-children path. The handler returns a small projection so the expected result stays compact.
 */
public class MapLargeResult extends DurableHandler<Object, Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Object input, DurableContext context) {
        String big = "x".repeat(70000);
        var config = MapConfig.builder().maxConcurrency(1).build();
        MapResult<String> result =
                context.map("large", List.of(0, 1, 2, 3), String.class, (item, index, ctx) -> big, config);

        var out = new LinkedHashMap<String, Object>();
        out.put("successCount", result.succeeded().size());
        out.put("totalCount", result.succeeded().size() + result.failed().size());
        return out;
    }
}
