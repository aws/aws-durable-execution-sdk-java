// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import software.amazon.lambda.durable.model.SafeCloseable;

/** Replay metadata available while an advanced extension CONTEXT framework callback is running. */
public final class ExtensionContextReplayContext<T> {
    private static final ThreadLocal<ExtensionContextReplayContext<?>> CURRENT = new ThreadLocal<>();

    private final boolean replayingChildren;
    private final boolean validatingReplay;
    private final T replayState;

    private ExtensionContextReplayContext(boolean replayingChildren, boolean validatingReplay, T replayState) {
        this.replayingChildren = replayingChildren;
        this.validatingReplay = validatingReplay;
        this.replayState = replayState;
    }

    /** Returns the replay context attached to the current extension framework thread. */
    @SuppressWarnings("unchecked")
    public static <T> ExtensionContextReplayContext<T> getCurrentContext() {
        var context = CURRENT.get();
        if (context == null) {
            throw new IllegalStateException("ExtensionContextReplayContext is not active on the current thread");
        }
        return (ExtensionContextReplayContext<T>) context;
    }

    /** Returns whether a completed CONTEXT is replaying its child operations. */
    public boolean isReplayingChildren() {
        return replayingChildren;
    }

    /** Returns whether a completed CONTEXT is re-entering its framework callback only to validate replay. */
    public boolean isValidatingReplay() {
        return validatingReplay;
    }

    /** Returns the checkpointed replay state, or {@code null} on initial execution. */
    public T getReplayState() {
        return replayState;
    }

    /** Attaches replay metadata for the duration of an SDK-managed framework callback. */
    public static <T> SafeCloseable attach(boolean replayingChildren, T replayState) {
        return attach(replayingChildren, false, replayState);
    }

    /** Attaches replay and validation metadata for the duration of an SDK-managed framework callback. */
    public static <T> SafeCloseable attach(boolean replayingChildren, boolean validatingReplay, T replayState) {
        var previous = CURRENT.get();
        CURRENT.set(new ExtensionContextReplayContext<>(replayingChildren, validatingReplay, replayState));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }
}
