// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.operation.dag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class DagExamplesTest {

    @Test
    void diamondExampleCompletes() {
        var runner = LocalDurableTestRunner.create(String.class, new DagDiamondExample());

        var result = runner.runUntilComplete("go");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ABAC", result.getResult(String.class));
    }

    @Test
    void compensationExampleReportsTaskStatuses() {
        var runner = LocalDurableTestRunner.create(String.class, new DagCompensationExample());

        var result = runner.runUntilComplete("go");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("COMPLETED_WITH_FAILURES|FAILED|SUCCEEDED|SKIPPED|SUCCEEDED", result.getResult(String.class));
    }

    @Test
    void runIfExampleReportsSkipCascade() {
        var runner = LocalDurableTestRunner.create(String.class, new DagRunIfExample());

        var result = runner.runUntilComplete("go");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("SKIPPED|SKIPPED", result.getResult(String.class));
    }

    @Test
    void waitResumeExampleCompletesAfterReplay() {
        var runner = LocalDurableTestRunner.create(String.class, new DagWaitResumeExample());

        var result = runner.runUntilComplete("go");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("ABAC", result.getResult(String.class));
    }
}
