// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.ErrorObject;

/** Exception thrown when the execution is not recoverable. The durable execution will be immediately terminated. */
public class UnrecoverableDurableExecutionException extends DurableExecutionException {
    private final ErrorObject errorObject;
    private final boolean retryable;

    /**
     * Creates an unrecoverable execution exception with an optional retry path and original cause.
     *
     * @param errorObject serialized error details
     * @param retryable whether the current invocation may be retried
     * @param cause original failure that caused this exception
     */
    public UnrecoverableDurableExecutionException(ErrorObject errorObject, boolean retryable, Throwable cause) {
        super(errorObject.errorMessage(), cause);
        this.errorObject = errorObject;
        this.retryable = retryable;
    }

    public UnrecoverableDurableExecutionException(ErrorObject errorObject, boolean retryable) {
        this(errorObject, retryable, null);
    }

    public UnrecoverableDurableExecutionException(ErrorObject errorObject) {
        this(errorObject, false, null);
    }

    /** Returns the error details for this unrecoverable exception. */
    public ErrorObject getErrorObject() {
        return errorObject;
    }

    /** Returns true if the execution can be retried. */
    public boolean isRetryable() {
        return retryable;
    }
}
