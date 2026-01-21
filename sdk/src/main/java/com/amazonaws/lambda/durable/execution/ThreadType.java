// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

/**
 * Thread type enum for tracking conceptual threads in durable execution.
 *
 * <p>These are not physical OS threads, but logical threads representing different types of work in the execution.
 */
public enum ThreadType {
    CONTEXT("Context"),
    STEP("Step");

    private final String displayName;

    ThreadType(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
