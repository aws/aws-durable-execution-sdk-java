// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Failed child-context scenario for OTel requirement otel-invocation-12. */
public final class Otel12ChildContextFailure extends OtelConformanceHandler<Void> {

    @Override
    public Void handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "child-context-failure");
        return context.runInChildContext("otel-failed-child-context", Void.class, child -> {
            throw new RuntimeException("Intentional child-context failure");
        });
    }
}
