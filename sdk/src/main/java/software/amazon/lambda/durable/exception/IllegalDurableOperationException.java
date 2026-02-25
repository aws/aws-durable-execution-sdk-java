// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

/** An illegal operation is detected. The execution will be immediately terminated. */
public class IllegalDurableOperationException extends UnrecoverableDurableExecutionException {
    public IllegalDurableOperationException(String message) {
        super(ErrorObject.builder()
                .errorType(IllegalDurableOperationException.class.getName())
                .errorMessage(message)
                .build());
    }
}
