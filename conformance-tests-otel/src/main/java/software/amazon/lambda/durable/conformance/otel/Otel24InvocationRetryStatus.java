// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;

/** Public invocation-retry signal and checkpoint recovery for OTel requirement 24. */
public final class Otel24InvocationRetryStatus extends OtelConformanceHandler<String> {

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "invocation-retry-status");
        var replayingAtEntry = context.isReplaying();
        context.step("otel-before-invocation-retry", String.class, step -> "saved");
        if (!replayingAtEntry) {
            throw new UnrecoverableDurableExecutionException(
                    ErrorObject.builder()
                            .errorType("ConformanceInvocationRetry")
                            .errorMessage("intentional-invocation-retry")
                            .build(),
                    true);
        }
        return "retry-complete";
    }
}
