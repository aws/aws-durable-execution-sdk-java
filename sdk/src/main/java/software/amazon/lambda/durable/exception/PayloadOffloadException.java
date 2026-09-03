// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

/** Thrown when a serialized payload cannot be stored in or loaded from external storage. */
public class PayloadOffloadException extends DurableExecutionException {
    public PayloadOffloadException(String message, Throwable cause) {
        super(message, cause);
    }

    public PayloadOffloadException(String message) {
        super(message);
    }
}
