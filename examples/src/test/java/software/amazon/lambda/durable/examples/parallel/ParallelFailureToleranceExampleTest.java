// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.parallel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ParallelFailureToleranceExampleTest {

    @Test
    void succeedsWhenFailuresAreWithinTolerance() {
        var handler = new ParallelFailureToleranceExample();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler);

        // 2 good services, 1 bad — toleratedFailureCount=1 so the parallel op still succeeds
        var input = new ParallelFailureToleranceExample.Input(List.of("svc-a", "bad-svc-b", "svc-c"), 1, null);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelFailureToleranceExample.Output.class);
        assertEquals(2, output.succeeded());
        assertEquals(1, output.failed());
    }

    @Test
    void succeedsWhenAllBranchesSucceed() {
        var handler = new ParallelFailureToleranceExample();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler);

        var input = new ParallelFailureToleranceExample.Input(List.of("svc-a", "svc-b", "svc-c"), 2, null);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelFailureToleranceExample.Output.class);
        assertEquals(3, output.succeeded());
    }

    @Test
    void failsWhenFailuresExceedTolerance() {
        var handler = new ParallelFailureToleranceExample();
        var runner = LocalDurableTestRunner.create(ParallelFailureToleranceExample.Input.class, handler);

        // Both failures must complete to exceed tolerance. A racing good branch need not finish before that point.
        var input = new ParallelFailureToleranceExample.Input(List.of("bad-svc-b", "bad-svc-c"), 1, 2);
        var result = runner.runUntilComplete(input);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(ParallelFailureToleranceExample.Output.class);
        assertEquals(2, output.failed());
        assertEquals(0, output.succeeded());
        var parallel = new JacksonSerDes()
                .deserialize(
                        result.getOperation("call-services").getContextDetails().result(),
                        TypeToken.get(ParallelResult.class));
        assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, parallel.completionStatus());
        assertEquals(2, parallel.failed());
    }
}
