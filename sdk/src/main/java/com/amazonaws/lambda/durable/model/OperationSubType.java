// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.model;

/**
 * Fine-grained classification of durable operations beyond the basic operation types.
 *
 * <p>Used as the {@code subType} field in checkpoint updates for {@code CONTEXT} operations. Matches the
 * {@code OperationSubType} enum in the JavaScript and Python durable execution SDKs.
 */
public enum OperationSubType {
    RUN_IN_CHILD_CONTEXT("RunInChildContext"),
    MAP("Map"),
    PARALLEL("Parallel");

    private final String value;

    OperationSubType(String value) {
        this.value = value;
    }

    /** Returns the wire-format string value sent in checkpoint updates. */
    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
