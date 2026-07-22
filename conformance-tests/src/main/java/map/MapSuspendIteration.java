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
 * 9-15: Map suspends inside an iteration; replay skips the completed iteration.
 *
 * <p>Iteration 1 issues a durable wait before its step, suspending the whole execution mid-map. On replay the completed
 * iteration 0 is skipped and iteration 1 resumes after the wait.
 */
public class MapSuspendIteration extends DurableHandler<Object, List<String>> {

    @Override
    public List<String> handleRequest(Object input, DurableContext context) {
        var config = MapConfig.builder().maxConcurrency(1).build();
        MapResult<String> result = context.map(
                "suspend",
                List.of("r0", "r1"),
                String.class,
                (item, index, ctx) -> {
                    if (index == 1) {
                        ctx.wait(null, Duration.ofSeconds(1));
                    }
                    return ctx.step("step-" + index, String.class, stepCtx -> item);
                },
                config);
        return result.results();
    }
}
