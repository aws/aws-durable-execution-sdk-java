// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/** 9-4: Map with an empty items list completes immediately with an empty results list. */
public class MapEmpty extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        MapResult<String> result = context.map(
                "empty",
                List.<String>of(),
                String.class,
                (item, index, ctx) -> item,
                MapConfig.builder().build());
        return result.results();
    }
}
