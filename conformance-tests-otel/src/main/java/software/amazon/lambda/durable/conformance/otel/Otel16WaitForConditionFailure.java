// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.Map;
import software.amazon.lambda.durable.DurableContext;

/** Failed wait-for-condition scenario for OTel requirement otel-invocation-16. */
public final class Otel16WaitForConditionFailure extends OtelConformanceHandler<Integer> {

    @Override
    public Integer handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "wait-for-condition-failure");
        return context.waitForCondition("otel-failed-condition", Integer.class, (state, step) -> {
            throw new RuntimeException("Intentional condition check failure");
        });
    }
}
