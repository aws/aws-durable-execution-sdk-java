// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SamplingUtilTest {

    @Test
    void samplingRate1_alwaysSamples() {
        assertTrue(SamplingUtil.shouldSampleExecution("arn:exec1", 1.0));
        assertTrue(SamplingUtil.shouldSampleExecution("arn:exec2", 1.0));
        assertTrue(SamplingUtil.shouldSampleExecution("anything", 1.0));
    }

    @Test
    void samplingRate0_neverSamples() {
        assertFalse(SamplingUtil.shouldSampleExecution("arn:exec1", 0.0));
        assertFalse(SamplingUtil.shouldSampleExecution("arn:exec2", 0.0));
    }

    @Test
    void deterministic_sameArnSameResult() {
        var arn = "arn:aws:lambda:us-east-1:123:function:test:$LATEST/durable/exec1";
        var result1 = SamplingUtil.shouldSampleExecution(arn, 0.5);
        var result2 = SamplingUtil.shouldSampleExecution(arn, 0.5);

        assertEquals(result1, result2, "Same ARN should always produce same sampling decision");
    }

    @Test
    void distribution_approximatesRate() {
        // With enough samples, the fraction sampled should approximate the rate
        int sampled = 0;
        int total = 10000;
        for (int i = 0; i < total; i++) {
            if (SamplingUtil.shouldSampleExecution("arn:exec-" + i, 0.5)) {
                sampled++;
            }
        }

        double actualRate = (double) sampled / total;
        // Allow 5% tolerance
        assertTrue(actualRate > 0.45 && actualRate < 0.55, "Sampling rate should be ~50%, got " + actualRate);
    }

    @Test
    void nullArn_returnsFalse() {
        assertFalse(SamplingUtil.shouldSampleExecution(null, 0.5));
    }

    @Test
    void emptyArn_returnsFalse() {
        assertFalse(SamplingUtil.shouldSampleExecution("", 0.5));
    }

    @Test
    void fnv1a32_isDeterministic() {
        var hash1 = SamplingUtil.fnv1a32("test-input");
        var hash2 = SamplingUtil.fnv1a32("test-input");

        assertEquals(hash1, hash2);
    }

    @Test
    void fnv1a32_differentInputsDifferentHashes() {
        var hash1 = SamplingUtil.fnv1a32("input-a");
        var hash2 = SamplingUtil.fnv1a32("input-b");

        assertNotEquals(hash1, hash2);
    }
}
