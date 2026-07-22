// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/**
 * 9-1: Map basic (one step per item, all succeed)
 *
 * <p>Each item runs in its own MapIteration child context and executes a single step returning a greeting.
 * Max-concurrency=1 for a deterministic history. Returns the ordered results list.
 */
public class MapBasic extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder().maxConcurrency(1).build();
        MapResult<String> result = context.map(
                "map",
                List.of("World", "Kiro"),
                String.class,
                (item, index, ctx) -> ctx.step("step-" + index, String.class, stepCtx -> "Hello, " + item + "!"),
                config);
        return result.results();
    }
}
