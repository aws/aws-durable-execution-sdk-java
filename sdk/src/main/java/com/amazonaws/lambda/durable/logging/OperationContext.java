// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.logging;

/** Current operation context for logging. Held in ThreadLocal for correct context in executor threads. */
public record OperationContext(String operationId, String operationName, Integer attempt) {}
