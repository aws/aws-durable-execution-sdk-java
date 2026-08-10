// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

/**
 * Evaluates one attempt of a stateful extension step.
 *
 * @param <T> the checkpointed state and final result type
 */
@FunctionalInterface
public interface ExtensionStepFunction<T> {
    /**
     * Evaluates the current state.
     *
     * @param state state restored from the prior retry checkpoint, or the configured initial state
     * @return a success or retry outcome
     */
    ExtensionStepResult<T> apply(T state);
}
