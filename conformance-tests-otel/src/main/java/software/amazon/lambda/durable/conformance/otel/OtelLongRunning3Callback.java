// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Delayed callback for OTel requirement otel-long-running-3. */
public final class OtelLongRunning3Callback extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "long-callback");
        longDelaySeconds(event);
        return context.waitForCallback("otel-long-callback", String.class, (callbackId, step) -> {});
    }
}
