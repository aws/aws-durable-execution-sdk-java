// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ParallelResultTest {

    @Test
    void allBranchesSucceed_countsAreCorrect() {
        var result = new ParallelResult(3, 3, 0, ConcurrencyCompletionStatus.ALL_COMPLETED);

        assertEquals(3, result.size());
        assertEquals(3, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());
    }
}
