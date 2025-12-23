// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

/**
 * Base interface for all durable operations (STEP, WAIT, etc.).
 *
 * <p>- execute() starts the operation (returns immediately) - get() blocks until complete and returns the result
 *
 * <p>The separation allows: - Starting multiple async operations quickly - Blocking on results later when needed -
 * Proper thread coordination via Phasers
 */
public interface DurableOperation<T> {

    /** Gets the unique identifier for this operation. */
    String getOperationId();

    /** Gets the operation name (may be null). */
    String getName();

    /** Starts the operation. Returns immediately after starting background work or checkpointing. Does not block. */
    void execute();

    /**
     * Blocks until the operation completes and returns the result.
     *
     * <p>Handles: - Thread deregistration (allows suspension) - Phaser blocking (waits for operation to complete) -
     * Thread reactivation (resumes execution) - Result retrieval
     *
     * @return the operation result
     */
    T get();
}
