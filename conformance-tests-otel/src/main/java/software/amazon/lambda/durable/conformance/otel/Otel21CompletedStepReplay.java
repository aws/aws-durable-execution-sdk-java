// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Completed-step replay scenario for OTel requirement 21 in both views. */
public final class Otel21CompletedStepReplay extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "completed-step-replay");
        var before = context.step("otel-before-wait", String.class, step -> "before");
        context.wait("otel-replay-wait", Duration.ofSeconds(1));
        var after = context.step("otel-after-wait", String.class, step -> "after");
        return before + "-" + after;
    }
}
