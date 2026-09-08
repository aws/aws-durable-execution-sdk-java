// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Failed wait-for-callback scenario for OTel requirement otel-invocation-17. */
public final class Otel17WaitForCallbackFailure extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "wait-for-callback-failure");
        return context.waitForCallback("otel-failed-callback", String.class, (callbackId, step) -> {});
    }
}
