// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Wait-for-callback scenario for OTel requirement otel-invocation-10. */
public final class Otel10WaitForCallback extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "wait-for-callback");
        return context.waitForCallback("otel-callback", String.class, (callbackId, step) -> {});
    }
}
