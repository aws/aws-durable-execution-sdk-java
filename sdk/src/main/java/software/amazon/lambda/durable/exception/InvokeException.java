// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.util.ExceptionHelper;

/** Base exception for chained invoke operation failures. */
public class InvokeException extends DurableOperationException {
    public InvokeException(Operation operation) {
        this(operation, null);
    }

    protected InvokeException(Operation operation, Throwable deserializedError) {
        super(
                operation,
                operation.chainedInvokeDetails() != null
                        ? operation.chainedInvokeDetails().error()
                        : null,
                operation.chainedInvokeDetails() != null
                                && operation.chainedInvokeDetails().error() != null
                        ? operation.chainedInvokeDetails().error().errorMessage()
                        : null,
                operation.chainedInvokeDetails() != null
                                && operation.chainedInvokeDetails().error() != null
                        ? ExceptionHelper.deserializeStackTrace(
                                operation.chainedInvokeDetails().error().stackTrace())
                        : null,
                deserializedError,
                deserializedError);
    }
}
