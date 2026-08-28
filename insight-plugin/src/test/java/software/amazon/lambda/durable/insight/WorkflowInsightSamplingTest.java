// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorkflowInsightSamplingTest {

    @Test
    void rateOneAlwaysSamplesIn() {
        assertTrue(WorkflowInsight.shouldSample("any-arn", 1.0));
    }

    @Test
    void rateZeroNeverSamplesIn() {
        assertFalse(WorkflowInsight.shouldSample("any-arn", 0.0));
    }

    @Test
    void fnv1a32IsDeterministic() {
        assertEquals(WorkflowInsight.fnv1a32("hello"), WorkflowInsight.fnv1a32("hello"));
    }

    @Test
    void resolveSamplingRateClampsOutOfRange() {
        assertEquals(1.0, WorkflowInsight.resolveSamplingRate(5.0));
        assertEquals(0.0, WorkflowInsight.resolveSamplingRate(-1.0));
        assertEquals(1.0, WorkflowInsight.resolveSamplingRate(null));
        assertEquals(0.25, WorkflowInsight.resolveSamplingRate(0.25));
    }

    @Test
    void samplingDecisionIsStableAcrossCalls() {
        String arn = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/e/i";
        assertEquals(WorkflowInsight.shouldSample(arn, 0.5), WorkflowInsight.shouldSample(arn, 0.5));
    }
}
