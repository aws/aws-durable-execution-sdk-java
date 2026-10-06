// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.math.BigDecimal;
import java.util.Map;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.otel.ExecutionOtelPlugin;
import software.amazon.lambda.durable.otel.InvocationOtelPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;

/**
 * Shared base for the OTel conformance suite's handlers. Ported from the otel-invocation/otel-execution examples in
 * aws/aws-durable-execution-conformance-tests. Each handler is deployed twice (see template.yaml): once for the
 * otel-invocation suite (default, loads {@link InvocationOtelPlugin}) and once for the otel-execution suite (loads
 * {@link ExecutionOtelPlugin} via the {@code OTEL_PLUGIN_MODE=execution} environment variable), against the X-Ray
 * backend only.
 */
abstract class OtelConformanceHandler<O> extends DurableHandler<Map<String, Object>, O> {

    protected OtelConformanceHandler() {
        super(new TypeToken<Map<String, Object>>() {});
    }

    @Override
    protected final DurableConfig createConfiguration() {
        return DurableConfig.builder().withPlugins(createPlugin()).build();
    }

    private DurableExecutionPlugin createPlugin() {
        return "execution".equals(System.getenv("OTEL_PLUGIN_MODE"))
                ? new ExecutionOtelPlugin()
                : new InvocationOtelPlugin();
    }

    protected final void requireScenario(Map<String, Object> event, String expected) {
        var actual = event.get("scenario");
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("Expected scenario " + expected + ", received " + actual);
        }
    }

    protected final long longDelaySeconds(Map<String, Object> event) {
        var rawDelay = event.get("delay_seconds");
        final long delay;
        try {
            // longValueExact rejects fractional values (1.5) and anything outside long range,
            // rather than silently truncating or wrapping them.
            delay = new BigDecimal(String.valueOf(rawDelay)).longValueExact();
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IllegalArgumentException("delay_seconds must be an integer from 1 through 86400", error);
        }
        if (delay < 1 || delay > 86400) {
            throw new IllegalArgumentException("delay_seconds must be an integer from 1 through 86400");
        }
        return delay;
    }
}
