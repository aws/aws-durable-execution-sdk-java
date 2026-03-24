// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.wait;

import java.util.stream.IntStream;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;

/**
 * Example demonstrating concurrent waitForCondition operations using map.
 *
 * <p>Runs many (totalOperations) waitForCondition operations concurrently (maxConcurrency). Each operation:
 *
 * <ol>
 *   <li>Uses attempt count as state (replay-safe).
 *   <li>Fails and retries until the attempt count reaches the given threshold, and then succeeds
 * </ol>
 */
public class ConcurrentWaitForConditionExample extends DurableHandler<ConcurrentWaitForConditionExample.Input, String> {

    public record Input(int threshold, int totalOperations, int maxConcurrency) {}

    @Override
    public String handleRequest(Input input, DurableContext context) {
        var items = IntStream.range(0, input.totalOperations()).boxed().toList();

        var config = MapConfig.builder().maxConcurrency(input.maxConcurrency()).build();

        var result = context.map(
                "concurrent-wait-for-conditions",
                items,
                String.class,
                (item, index, ctx) -> {
                    var conditionConfig = WaitForConditionConfig.<Integer>builder()
                            .initialState(1)
                            .build();
                    // Poll until the counter reaches the input threshold
                    var count = ctx.waitForCondition(
                            "condition-" + index,
                            Integer.class,
                            (callCount, stepCtx) -> {
                                if (callCount >= input.threshold()) {
                                    return WaitForConditionResult.stopPolling(callCount);
                                }
                                return WaitForConditionResult.continuePolling(callCount + 1);
                            },
                            conditionConfig);
                    return String.valueOf(count);
                },
                config);

        return String.join(" | ", result.results());
    }
}
