// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Suspending child for OTel view case 27. */
public final class Otel27InvokeTarget extends OtelConformanceHandler<Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "chained-invoke-suspend");
        context.wait("otel-suspended-target-wait", Duration.ofSeconds(1));
        return event;
    }
}
