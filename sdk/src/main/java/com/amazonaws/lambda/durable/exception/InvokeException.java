// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

public class InvokeException extends DurableOperationException {
    public InvokeException(Operation operation, ErrorObject errorObject) {
        super(operation, errorObject);
    }
}
