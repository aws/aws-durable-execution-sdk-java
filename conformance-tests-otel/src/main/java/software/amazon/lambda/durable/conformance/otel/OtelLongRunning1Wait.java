// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Long durable wait for OTel requirement otel-long-running-1. */
public final class OtelLongRunning1Wait extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "long-wait");
        context.wait("otel-long-wait", Duration.ofSeconds(longDelaySeconds(event)));
        return context.step("otel-after-long-wait", String.class, step -> "resumed");
    }
}
