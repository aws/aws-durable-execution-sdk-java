// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import software.amazon.lambda.durable.model.SafeCloseable;

/** Metadata for the retry body active on the current SDK-managed thread. */
public final class WithRetryContext {
    private static final OperationContextStorage<WithRetryContext> CURRENT =
            new OperationContextStorage<>("WithRetryContext");

    private final int attempt;

    private WithRetryContext(int attempt) {
        this.attempt = attempt;
    }

    /** Returns the retry context attached to the current SDK-managed thread. */
    public static WithRetryContext getCurrentContext() {
        return CURRENT.getCurrentContext();
    }

    /** Returns the current one-based retry attempt. */
    public int getAttempt() {
        return attempt;
    }

    static SafeCloseable attach(int attempt) {
        return CURRENT.attach(new WithRetryContext(attempt));
    }
}
