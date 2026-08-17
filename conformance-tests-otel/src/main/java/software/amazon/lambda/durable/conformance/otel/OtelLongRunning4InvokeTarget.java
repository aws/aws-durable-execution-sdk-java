// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Delayed target for OTel requirement otel-long-running-4. */
public final class OtelLongRunning4InvokeTarget extends OtelConformanceHandler<Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "long-chained-invoke");
        context.wait("otel-long-invoke-target-wait", Duration.ofSeconds(longDelaySeconds(event)));
        return event;
    }
}
