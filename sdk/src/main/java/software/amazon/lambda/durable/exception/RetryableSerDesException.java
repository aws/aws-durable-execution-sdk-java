// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.exception;

/**
 * Indicates a transient serialization or deserialization failure that may succeed when retried.
 *
 * <p>{@link software.amazon.lambda.durable.serde.RetrySerDesStage} and
 * {@link software.amazon.lambda.durable.serde.RetryBinarySerDesStage} retry only this exception type. Other
 * {@link SerDesException} instances are treated as permanent failures.
 */
public class RetryableSerDesException extends SerDesException {
    public RetryableSerDesException(String message, Throwable cause) {
        super(message, cause);
    }

    public RetryableSerDesException(String message) {
        super(message);
    }
}
