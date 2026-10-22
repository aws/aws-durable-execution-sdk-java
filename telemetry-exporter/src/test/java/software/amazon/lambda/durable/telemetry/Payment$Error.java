// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

/**
 * A top-level user error whose declared name contains {@code '$'}, which is legal Java. Its binary name is
 * {@code software.amazon.lambda.durable.telemetry.Payment$Error}, so Table A reduces it to {@code Error} (row B18).
 */
public class Payment$Error extends RuntimeException {
    public Payment$Error(String message) {
        super(message);
    }
}
