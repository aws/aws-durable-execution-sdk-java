// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import software.amazon.lambda.durable.context.BaseContext;

public interface StepContext extends BaseContext {
    /** Returns the current retry attempt number (0-based). */
    int getAttempt();

    /** Returns the step context attached to the current SDK-managed thread. */
    static StepContext getCurrentContext() {
        var context = BaseContext.getCurrentContext();
        if (context instanceof StepContext stepContext) {
            return stepContext;
        }
        if (context == null) {
            throw new IllegalStateException("No StepContext is active on the current thread");
        }
        throw new IllegalStateException(
                "StepContext is not available from a durable context thread; use DurableContext.getCurrentContext() instead");
    }
}
