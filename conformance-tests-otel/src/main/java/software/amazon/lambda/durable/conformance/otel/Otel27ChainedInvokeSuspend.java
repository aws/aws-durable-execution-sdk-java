// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;

/** Chained invoke with child suspension for OTel view case 27. */
public final class Otel27ChainedInvokeSuspend extends OtelConformanceHandler<Map<String, Object>> {

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "chained-invoke-suspend");
        return context.invoke(
                "otel-suspended-invoke",
                System.getenv("OTEL_INVOKE_TARGET_FUNCTION_NAME"),
                event,
                new TypeToken<Map<String, Object>>() {});
    }
}
