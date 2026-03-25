// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.types;

/** Input for the approval workflow. */
public record ApprovalRequest(String description, double amount, Integer timeoutSeconds) {
    // Convenience constructor for default timeout
    public ApprovalRequest(String description, double amount) {
        this(description, amount, null);
    }
}
