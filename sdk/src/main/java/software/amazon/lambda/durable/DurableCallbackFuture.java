// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

/**
 * Result of creating a callback, containing the callback ID and providing access to the result. Extends DurableFuture
 * so callbacks can be processed the same way as other futures.
 */
public interface DurableCallbackFuture<T> extends DurableFuture<T> {
    /**
     * Returns the unique identifier for this callback.
     *
     * <p>External systems use this ID to send callback results back to the durable execution.
     *
     * @return the callback ID
     */
    String callbackId();
}
