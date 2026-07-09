// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConcurrencyCompletionStatusTest {

    @Test
    void isSucceeded_returnsFlagFromStatus() {
        assertTrue(ConcurrencyCompletionStatus.ALL_COMPLETED.isSucceeded());
        assertTrue(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED.isSucceeded());
        assertFalse(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED.isSucceeded());
        assertTrue(ConcurrencyCompletionStatus.CUSTOM_COMPLETION_SUCCEEDED.isSucceeded());
        assertFalse(ConcurrencyCompletionStatus.CUSTOM_COMPLETION_FAILED.isSucceeded());
    }
}
