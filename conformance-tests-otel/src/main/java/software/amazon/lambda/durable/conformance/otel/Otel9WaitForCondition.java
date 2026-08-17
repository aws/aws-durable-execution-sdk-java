// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.WaitStrategies;

/** Wait-for-condition scenario for OTel requirement otel-invocation-9. */
public final class Otel9WaitForCondition extends OtelConformanceHandler<Integer> {

    @Override
    public Integer handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "wait-for-condition");
        return context.waitForCondition(
                "otel-condition",
                Integer.class,
                (state, step) -> {
                    var next = state + 1;
                    return next >= 2
                            ? WaitForConditionResult.stopPolling(next)
                            : WaitForConditionResult.continuePolling(next);
                },
                WaitForConditionConfig.<Integer>builder()
                        .initialState(0)
                        .waitStrategy(WaitStrategies.fixedDelay(3, Duration.ofSeconds(1)))
                        .build());
    }
}
