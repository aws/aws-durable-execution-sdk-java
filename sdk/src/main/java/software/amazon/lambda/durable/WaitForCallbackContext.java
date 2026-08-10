// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.Objects;
import software.amazon.lambda.durable.model.SafeCloseable;

/** Metadata for the callback submitter active on the current SDK-managed thread. */
public final class WaitForCallbackContext {
    private static final OperationContextStorage<WaitForCallbackContext> CURRENT =
            new OperationContextStorage<>("WaitForCallbackContext");

    private final String callbackId;

    private WaitForCallbackContext(String callbackId) {
        this.callbackId = Objects.requireNonNull(callbackId, "callbackId cannot be null");
    }

    /** Returns the callback context attached to the current SDK-managed thread. */
    public static WaitForCallbackContext getCurrentContext() {
        return CURRENT.getCurrentContext();
    }

    /** Returns the callback ID to send to the external system. */
    public String getCallbackId() {
        return callbackId;
    }

    /** Attaches callback metadata for the duration of the returned scope. */
    public static SafeCloseable attach(String callbackId) {
        return CURRENT.attach(new WaitForCallbackContext(callbackId));
    }
}
