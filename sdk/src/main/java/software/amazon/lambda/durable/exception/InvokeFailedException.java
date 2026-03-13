// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;

/** Thrown when a chained invoke operation fails with an error in the invoked function. */
public class InvokeFailedException extends InvokeException {

    public InvokeFailedException(Operation operation) {
        super(operation);
    }
}
