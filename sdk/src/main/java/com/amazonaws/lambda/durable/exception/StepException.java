// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;

public class StepException extends DurableOperationException {
    public StepException(Operation operation, ErrorObject errorObject, String errorMessage) {
        super(operation, errorObject, errorMessage);
    }
}
