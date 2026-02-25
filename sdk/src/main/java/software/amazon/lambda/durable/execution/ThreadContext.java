// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

/** Holds the current thread's execution context. */
public record ThreadContext(String threadId, ThreadType threadType) {}
