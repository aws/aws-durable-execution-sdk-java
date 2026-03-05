// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;

/** Exception thrown when a callback submitter step fails to submit a callback. */
public class CallbackSubmitterException extends CallbackException {
    public CallbackSubmitterException(Operation callbackOp, StepException stepEx) {
        super(callbackOp, stepEx.getMessage(), stepEx);
    }
}
