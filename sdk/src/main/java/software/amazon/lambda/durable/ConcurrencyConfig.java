// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

public record ConcurrencyConfig(int maxConcurrency, int minSuccessful, int toleratedFailureCount) {}
