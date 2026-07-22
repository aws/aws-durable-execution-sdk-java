// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/** 9-11: Map real concurrency (max-concurrency=2) preserves index-ordered results regardless of completion order. */
public class MapConcurrent extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder().maxConcurrency(2).build();
        MapResult<String> result =
                context.map("concurrent", List.of("r0", "r1", "r2"), String.class, (item, index, ctx) -> item, config);
        return result.results();
    }
}
