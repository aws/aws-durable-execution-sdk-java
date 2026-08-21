// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import software.amazon.lambda.durable.context.BaseContext;

public interface StepContext extends BaseContext {
    /** Returns the current retry attempt number (1-based). */
    int getAttempt();

    /**
     * Returns the step context attached to the current SDK-managed thread.
     *
     * @return the current step context, or {@code null} when no SDK context is active
     * @throws IllegalStateException if called from a durable context thread
     */
    static StepContext getCurrentContext() {
        var context = BaseContext.getCurrentContext();
        if (context instanceof StepContext stepContext) {
            return stepContext;
        }
        if (context == null) {
            return null;
        }
        throw new IllegalStateException(
                "StepContext is not available from a durable context thread; use DurableContext.getCurrentContext() instead");
    }

    /**
     * Requires the step context attached to the current SDK-managed thread.
     *
     * @return the current step context
     * @throws IllegalStateException if called outside a step thread or from a durable context thread
     */
    static StepContext requireCurrentContext() {
        var context = getCurrentContext();
        if (context == null) {
            throw new IllegalStateException("No StepContext is active on the current thread");
        }
        return context;
    }
}
