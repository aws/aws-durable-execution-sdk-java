// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Interrupted wait scenario for OTel requirement otel-invocation-15. */
public final class Otel15WaitInterrupted extends OtelConformanceHandler<Void> {

    @Override
    public Void handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "wait-interrupted");
        return context.wait("otel-interrupted-wait", Duration.ofSeconds(30));
    }
}
