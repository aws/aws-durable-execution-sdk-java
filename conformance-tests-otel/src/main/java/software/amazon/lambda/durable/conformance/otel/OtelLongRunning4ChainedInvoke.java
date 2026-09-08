// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;

/** Long chained invoke for OTel requirement otel-long-running-4. */
public final class OtelLongRunning4ChainedInvoke extends OtelConformanceHandler<Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "long-chained-invoke");
        return context.invoke(
                "otel-long-invoke",
                System.getenv("OTEL_INVOKE_TARGET_FUNCTION_NAME"),
                event,
                new TypeToken<Map<String, Object>>() {});
    }
}
