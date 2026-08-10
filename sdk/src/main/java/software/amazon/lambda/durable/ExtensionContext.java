// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import software.amazon.lambda.durable.context.BaseContext;

/**
 * Public context available to custom extension operations.
 *
 * <p>This interface exposes replay state and opaque primitive reservations without exposing checkpoint internals or
 * raw operation IDs.
 */
public interface ExtensionContext extends BaseContext {
    /**
     * Returns the extension context attached to the current SDK-managed context thread.
     *
     * @return the current extension context
     * @throws IllegalStateException if called outside a durable context or from a step thread
     */
    static ExtensionContext getCurrentContext() {
        var context = BaseContext.getCurrentContext();
        if (context instanceof ExtensionContext extensionContext) {
            return extensionContext;
        }
        if (context == null) {
            throw new IllegalStateException("No ExtensionContext is active on the current thread");
        }
        throw new IllegalStateException(
                "ExtensionContext is not available from a step thread; use StepContext.getCurrentContext() instead");
    }

    /** Returns whether this extension scope is replaying checkpointed operations. */
    boolean isReplaying();

    /**
     * Reserves the next sequential primitive operation identity.
     *
     * @param name the primitive operation name
     * @return an opaque one-shot reservation
     */
    ExtensionOperation reserve(String name);
}
