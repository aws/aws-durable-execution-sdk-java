// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ParallelResultTest {

    @Test
    void allBranchesSucceed_countsAreCorrect() {
        var result = new ParallelResult(
                3,
                3,
                0,
                0,
                ConcurrencyCompletionStatus.ALL_COMPLETED,
                List.of(
                        ParallelResult.Status.SUCCEEDED,
                        ParallelResult.Status.SUCCEEDED,
                        ParallelResult.Status.SUCCEEDED));

        assertEquals(3, result.size());
        assertEquals(3, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(0, result.skipped());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());
        assertTrue(result.statuses().stream().allMatch(status -> status == ParallelResult.Status.SUCCEEDED));
    }
}
