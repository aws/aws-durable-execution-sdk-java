// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.operation;

/**
 * Base interface for all durable operations (STEP, WAIT, etc.).
 *
 * <p>Key methods:
 *
 * <ul>
 *   <li>{@code execute()} starts the operation (returns immediately)
 *   <li>{@code get()} blocks until complete and returns the result
 * </ul>
 *
 * <p>The separation allows:
 *
 * <ul>
 *   <li>Starting multiple async operations quickly
 *   <li>Blocking on results later when needed
 *   <li>Proper thread coordination via Phasers
 * </ul>
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
     * <p>Handles:
     *
     * <ul>
     *   <li>Thread deregistration (allows suspension)
     *   <li>Phaser blocking (waits for operation to complete)
     *   <li>Thread reactivation (resumes execution)
     *   <li>Result retrieval
     * </ul>
     *
     * @return the operation result
     */
    T get();
}
