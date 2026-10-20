// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.ChildContextFailedException;

/** Catches an externally failed callback, then resumes twice before returning successfully. */
public final class Otel25CaughtCallbackFailureReplay extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "caught-callback-failure-replay");
        var caught = false;
        try {
            context.waitForCallback("otel-failed-callback", String.class, (callbackId, step) -> {});
        } catch (CallbackFailedException | ChildContextFailedException expected) {
            caught = true;
        }
        if (!caught) throw new IllegalStateException("Expected the first callback to fail");
        var observed = context.step("otel-failed-callback-observed", String.class, step -> "caught");
        var one = context.waitForCallback("otel-failed-callback-barrier-one", String.class, (callbackId, step) -> {});
        var two = context.waitForCallback("otel-failed-callback-barrier-two", String.class, (callbackId, step) -> {});
        return observed + "/" + one + "/" + two;
    }
}
