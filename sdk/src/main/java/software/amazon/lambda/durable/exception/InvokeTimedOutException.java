// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

import software.amazon.awssdk.services.lambda.model.Operation;

public class InvokeTimedOutException extends InvokeException {

    public InvokeTimedOutException(Operation operation) {
        super(operation);
    }
}
