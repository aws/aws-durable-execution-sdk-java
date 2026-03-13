// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

/**
 * Output payload returned by the Lambda handler to the Durable Functions backend.
 *
 * @param status the execution status (SUCCEEDED, FAILED, or PENDING)
 * @param result the serialized result on success, or null otherwise
 * @param error the error details on failure, or null otherwise
 */
public record DurableExecutionOutput(ExecutionStatus status, String result, ErrorObject error) {

    /** Creates a successful output with the given serialized result. */
    public static DurableExecutionOutput success(String result) {
        return new DurableExecutionOutput(ExecutionStatus.SUCCEEDED, result, null);
    }

    /** Creates a pending output indicating the execution was suspended. */
    public static DurableExecutionOutput pending() {
        return new DurableExecutionOutput(ExecutionStatus.PENDING, null, null);
    }

    /** Creates a failed output with the given error details. */
    public static DurableExecutionOutput failure(ErrorObject errorObject) {
        return new DurableExecutionOutput(ExecutionStatus.FAILED, null, errorObject);
    }
}
