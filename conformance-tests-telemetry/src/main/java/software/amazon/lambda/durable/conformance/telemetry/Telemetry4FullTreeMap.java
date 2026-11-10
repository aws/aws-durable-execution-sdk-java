// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.telemetry;

import java.util.List;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.MapConfig;

/**
 * Full-tree operation detail for requirement telemetry-4.
 *
 * <p>The map {@code process-items} runs one iteration per item with max concurrency 1. The item namer names each
 * iteration {@code item-<item>}. Each iteration runs one step {@code greet-<item>}. The plugin runs with full-tree
 * operation detail. So the record keeps the map, both iteration contexts, and both steps, one key per name.
 *
 * <p>The handler uses the event as the item list. The runner sends {@code [1, 2]}. The handler constructs no record and
 * calls no exporter method. The plugin builds and exports the record.
 */
public final class Telemetry4FullTreeMap extends DurableHandler<List<Integer>, List<String>> {

    // Class initialization fails when the variable is unset. See TelemetryConformanceConfig.requireLogGroupName.
    private static final String LOG_GROUP_NAME =
            TelemetryConformanceConfig.requireLogGroupName(System.getenv(TelemetryConformanceConfig.LOG_GROUP_ENV));

    @Override
    protected DurableConfig createConfiguration() {
        return TelemetryConformanceConfig.durableConfig(
                TelemetryConformanceConfig.fullTreeMapTelemetryConfig(LOG_GROUP_NAME));
    }

    @Override
    public List<String> handleRequest(List<Integer> input, DurableContext context) {
        var config = MapConfig.builder()
                .maxConcurrency(1)
                .itemNamer((item, index) -> "item-" + item)
                .build();
        return context.map(
                        TelemetryConformanceConfig.FULL_TREE_MAP_NAME,
                        input,
                        String.class,
                        (item, index, ctx) ->
                                ctx.step("greet-" + item, String.class, stepCtx -> "Hello, " + item + "!"),
                        config)
                .results();
    }
}
