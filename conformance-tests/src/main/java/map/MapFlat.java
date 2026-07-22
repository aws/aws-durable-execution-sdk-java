// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.model.MapResult;

/**
 * 9-12: Map with FLAT nesting (virtual iteration contexts). Each item's step is checkpointed directly under the parent
 * Map context; no per-iteration MapIteration context events are emitted.
 */
public class MapFlat extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .nestingType(NestingType.FLAT)
                .build();
        MapResult<String> result = context.map(
                "flat",
                List.of("fa", "fb"),
                String.class,
                (item, index, ctx) -> ctx.step("step-" + index, String.class, stepCtx -> item),
                config);
        return result.results();
    }
}
