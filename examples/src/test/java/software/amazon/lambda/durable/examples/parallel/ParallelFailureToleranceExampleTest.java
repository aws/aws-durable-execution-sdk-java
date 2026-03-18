// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.parallel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ParallelFailureToleranceExampleTest {

    @Test
    void succeedsWhenFailuresAreWithinTolerance() {
        var handler = new ParallelFailureToleranceExample();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler);

        // 2 good services, 1 bad — toleratedFailureCount=1 so the parallel op still succeeds
        var input = new ParallelFailureToleranceExample.Input(List.of("svc-a", "bad-svc-b", "svc-c"), 1);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelFailureToleranceExample.Output.class);
        assertEquals(2, output.succeeded().size());
        assertEquals(1, output.failed().size());
        assertTrue(output.succeeded().contains("ok:svc-a"));
        assertTrue(output.succeeded().contains("ok:svc-c"));
        assertTrue(output.failed().contains("bad-svc-b"));
    }

    @Test
    void succeedsWhenAllBranchesSucceed() {
        var handler = new ParallelFailureToleranceExample();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler);

        var input = new ParallelFailureToleranceExample.Input(List.of("svc-a", "svc-b", "svc-c"), 2);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelFailureToleranceExample.Output.class);
        assertEquals(3, output.succeeded().size());
        assertTrue(output.failed().isEmpty());
    }

    @Test
    void failsWhenFailuresExceedTolerance() {
        var handler = new ParallelFailureToleranceExample();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler);

        // 2 bad services, toleratedFailureCount=1 — second failure exceeds tolerance
        var input = new ParallelFailureToleranceExample.Input(List.of("svc-a", "bad-svc-b", "bad-svc-c"), 1);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelFailureToleranceExample.Output.class);
        assertEquals(2, output.failed().size());
        assertEquals(1, output.succeeded().size());
    }
}
