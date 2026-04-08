// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.types;

public record ManyAsyncStepsOutput(long result, long executionTimeMs, long replayTimeMs) {}
