// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.config.MapConfig;

/** Failed map-iteration scenario for OTel requirement otel-invocation-14. */
public final class Otel14MapFailure extends OtelConformanceHandler<Void> {

    @Override
    public Void handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "map-failure");
        var result = context.map(
                "otel-failed-map",
                List.of(1),
                Void.class,
                (item, index, iteration) -> {
                    throw new RuntimeException("Intentional map iteration failure");
                },
                MapConfig.builder().maxConcurrency(1).build());
        if (!result.allSucceeded()) {
            throw new RuntimeException("Intentional map failure");
        }
        return null;
    }
}
