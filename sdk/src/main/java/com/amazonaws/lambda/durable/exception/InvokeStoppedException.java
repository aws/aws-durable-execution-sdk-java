// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;

public class InvokeStoppedException extends InvokeException {

    public InvokeStoppedException(Operation operation) {
        super(operation);
    }
}
