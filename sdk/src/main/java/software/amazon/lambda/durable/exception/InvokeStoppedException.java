// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;

/** Thrown when a chained invoke operation is stopped before completion. */
public class InvokeStoppedException extends InvokeException {

    public InvokeStoppedException(Operation operation) {
        super(operation);
    }
}
