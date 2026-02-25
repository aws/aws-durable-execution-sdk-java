// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

/** Exception thrown when serialization or deserialization fails. */
public class SerDesException extends DurableExecutionException {
    public SerDesException(String message, Throwable cause) {
        super(message, cause);
    }

    public SerDesException(String message) {
        super(message);
    }
}
