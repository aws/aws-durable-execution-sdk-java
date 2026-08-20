// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.retry.RetryStrategies;

/** Long retry delay for OTel requirement otel-long-running-2. */
public final class OtelLongRunning2Retry extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "long-retry");
        return context.step(
                "otel-long-retry",
                String.class,
                step -> {
                    if (step.getAttempt() < 2) {
                        throw new RuntimeException("Intentional first-attempt failure");
                    }
                    return "retried";
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.fixedDelay(2, Duration.ofSeconds(longDelaySeconds(event))))
                        .build());
    }
}
