// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Child-context hierarchy scenario for OTel requirement otel-invocation-5. */
public final class Otel5ChildContext extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "child-context");
        return context.runInChildContext(
                "otel-child-context",
                String.class,
                child -> child.step("otel-child-step", String.class, step -> "child-complete"));
    }
}
