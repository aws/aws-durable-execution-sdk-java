// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Observes a root callback completion once, then revisits it through two later callback resumes. */
public final class Otel26ExternalCompletionReplay extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "external-callback-completion-replay");
        var target = context.createCallback("otel-external-target", String.class).get();
        var observed = context.step("otel-external-target-observed", String.class, step -> target);
        var one = context.waitForCallback("otel-external-barrier-one", String.class, (callbackId, step) -> {});
        var two = context.waitForCallback("otel-external-barrier-two", String.class, (callbackId, step) -> {});
        return observed + "/" + one + "/" + two;
    }
}
