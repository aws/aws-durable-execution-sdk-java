// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import java.math.BigDecimal;
import java.util.Map;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;

/**
 * Shared base for the OTel conformance suite's handlers. Ported from the otel-invocation/otel-execution examples in
 * aws/aws-durable-execution-conformance-tests. Each handler is deployed twice (see template.yaml): once for the
 * otel-invocation suite and once for the otel-execution suite. Both plugins are discovered from the deployed Lambda
 * layer through {@code DURABLE_EXECUTION_PLUGINS}; the function artifact does not contain or explicitly register the
 * OTel plugin.
 */
abstract class OtelConformanceHandler<O> extends DurableHandler<Map<String, Object>, O> {

    protected OtelConformanceHandler() {
        super(new TypeToken<Map<String, Object>>() {});
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
