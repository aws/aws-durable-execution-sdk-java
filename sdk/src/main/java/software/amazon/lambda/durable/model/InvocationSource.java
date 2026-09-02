// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/** Identifies how a durable execution invocation was started. */
public enum InvocationSource {
    DIRECT,
    CHAINED_INVOKE
}
