// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/** 9-3: Map function receives item and index (returns item + index directly, no inner step). */
public class MapItemIndex extends DurableHandler<Object, List<Integer>> {

    @Override
    public List<Integer> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder().maxConcurrency(1).build();
        MapResult<Integer> result =
                context.map("indexed", List.of(10, 20, 30), Integer.class, (item, index, ctx) -> item + index, config);
        return result.results();
    }
}
