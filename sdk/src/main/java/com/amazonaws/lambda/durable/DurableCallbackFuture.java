// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package com.amazonaws.lambda.durable;

/** Result of creating a callback, containing the callback ID and a future for the result. */
public record DurableCallbackFuture<T>(String callbackId, DurableFuture<T> future) {}
