// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;

/**
 * Exception thrown when a {@code waitForCondition} operation fails.
 *
 * <p>This can occur when the maximum number of polling attempts is exceeded, or when the check function throws an
 * error.
 */
public class WaitForConditionException extends DurableOperationException {

    public WaitForConditionException(String message) {
        super(null, null, message);
    }

    public WaitForConditionException(Operation operation) {
        super(
                operation,
                operation.stepDetails() != null ? operation.stepDetails().error() : null);
    }
}
