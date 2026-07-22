// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package map;

import java.time.Duration;
import java.util.List;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.model.MapResult;

/**
 * 9-17: Suspension after a successful map (replay skips the completed map).
 *
 * <p>All iterations succeed, then a durable wait suspends the execution. On replay the completed map is skipped.
 */
public class MapThenWait extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder().maxConcurrency(1).build();
        MapResult<String> result = context.map(
                "then-wait", List.of("a", "b"), String.class, (item, index, ctx) -> item.toUpperCase(), config);
        context.wait(null, Duration.ofSeconds(1));
        return result.results();
    }
}
