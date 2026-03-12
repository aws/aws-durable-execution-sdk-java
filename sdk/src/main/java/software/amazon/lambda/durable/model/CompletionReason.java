// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/** Indicates why a concurrent operation (map or parallel) completed. */
public enum CompletionReason {
    ALL_COMPLETED,
    MIN_SUCCESSFUL_REACHED,
    FAILURE_TOLERANCE_EXCEEDED
}
