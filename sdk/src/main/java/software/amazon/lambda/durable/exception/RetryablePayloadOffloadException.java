// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

/** Signals a transient payload storage failure that may be retried by a configured offloader wrapper. */
public class RetryablePayloadOffloadException extends PayloadOffloadException {
    public RetryablePayloadOffloadException(String message, Throwable cause) {
        super(message, cause);
    }

    public RetryablePayloadOffloadException(String message) {
        super(message);
    }
}
