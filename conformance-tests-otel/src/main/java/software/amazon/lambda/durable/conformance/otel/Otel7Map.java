// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.util.List;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.config.MapConfig;

/** Map hierarchy scenario for OTel requirement otel-invocation-7. */
public final class Otel7Map extends OtelConformanceHandler<List<Integer>> {

    @Override
    public List<Integer> handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "map-hierarchy");
        return context.map(
                        "otel-map",
                        List.of(1, 2),
                        Integer.class,
                        (item, index, iteration) ->
                                iteration.step("otel-map-step-" + index, Integer.class, step -> item * 2),
                        MapConfig.builder().maxConcurrency(1).build())
                .results();
    }
}
