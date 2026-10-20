// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DurableExecutionTelemetrySamplingTest {

    @Test
    void rateOneAlwaysSamplesIn() {
        assertTrue(DurableExecutionTelemetry.shouldSample("any-arn", 1.0));
    }

    @Test
    void rateZeroNeverSamplesIn() {
        assertFalse(DurableExecutionTelemetry.shouldSample("any-arn", 0.0));
    }

    @Test
    void fnv1a32IsDeterministic() {
        assertEquals(DurableExecutionTelemetry.fnv1a32("hello"), DurableExecutionTelemetry.fnv1a32("hello"));
    }

    @Test
    void resolveSamplingRateClampsOutOfRange() {
        assertEquals(1.0, DurableExecutionTelemetry.resolveSamplingRate(5.0));
        assertEquals(0.0, DurableExecutionTelemetry.resolveSamplingRate(-1.0));
        assertEquals(1.0, DurableExecutionTelemetry.resolveSamplingRate(null));
        assertEquals(0.25, DurableExecutionTelemetry.resolveSamplingRate(0.25));
    }

    @Test
    void samplingDecisionIsStableAcrossCalls() {
        String arn = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/e/i";
        assertEquals(
                DurableExecutionTelemetry.shouldSample(arn, 0.5), DurableExecutionTelemetry.shouldSample(arn, 0.5));
    }
}
