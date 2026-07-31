// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/** 9-13: Map with a custom item namer (names each iteration from its item). */
public class MapItemNamer extends DurableHandler<Object, List<Integer>> {

    @Override
    public List<Integer> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .itemNamer((item, index) -> "item-" + item)
                .build();
        MapResult<Integer> result =
                context.map("named-items", List.of(1, 2), Integer.class, (item, index, ctx) -> item * 10, config);
        return result.results();
    }
}
