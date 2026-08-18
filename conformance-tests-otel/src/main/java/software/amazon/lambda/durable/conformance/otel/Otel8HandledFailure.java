// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** Handled step failure scenario for OTel requirement otel-invocation-8. */
public final class Otel8HandledFailure extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "handled-failure");
        try {
            context.step(
                    "otel-handled-failure",
                    Void.class,
                    step -> {
                        throw new RuntimeException("Intentional handled failure");
                    },
                    StepConfig.builder()
                            .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                            .build());
        } catch (RuntimeException expected) {
            // Continue into the recovery step after recording the failed operation.
        }
        return context.step("otel-recovery-step", String.class, step -> "recovered");
    }
}
