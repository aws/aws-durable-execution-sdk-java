// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ParallelBranchSkippedExceptionTest {

    @Test
    void namesTheSkippedBranchInTheMessage() {
        var exception = new ParallelBranchSkippedException("branch-b");
        assertEquals("branch-b", exception.getBranchName());
        assertEquals("Parallel branch 'branch-b' was skipped", exception.getMessage());
    }

    @Test
    void formatsAFallbackMessageWhenTheBranchNameIsNull() {
        var exception = new ParallelBranchSkippedException(null);
        assertNull(exception.getBranchName());
        assertEquals("Parallel branch was skipped", exception.getMessage());
    }
}
