// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

/** Result and replay policy returned by an advanced extension CONTEXT primitive. */
public final class ExtensionContextResult<T> {
    private enum ReplayPolicy {
        NONE,
        ALWAYS,
        ABOVE_SIZE
    }

    private final T result;
    private final T replayState;
    private final ReplayPolicy replayPolicy;
    private final int thresholdBytes;

    private ExtensionContextResult(T result, T replayState, ReplayPolicy replayPolicy, int thresholdBytes) {
        this.result = result;
        this.replayState = replayState;
        this.replayPolicy = replayPolicy;
        this.thresholdBytes = thresholdBytes;
    }

    /** Returns a normal context result that does not replay children. */
    public static <T> ExtensionContextResult<T> completed(T result) {
        return new ExtensionContextResult<>(result, null, ReplayPolicy.NONE, 0);
    }

    /** Returns a result that always replays children using the supplied replay state. */
    public static <T> ExtensionContextResult<T> replayChildren(T result, T replayState) {
        return new ExtensionContextResult<>(result, replayState, ReplayPolicy.ALWAYS, 0);
    }

    /** Returns a result that replays children when the serialized full result reaches the threshold. */
    public static <T> ExtensionContextResult<T> replayChildrenAboveSize(T result, T replayState, int thresholdBytes) {
        if (thresholdBytes <= 0) {
            throw new IllegalArgumentException("thresholdBytes must be greater than zero");
        }
        return new ExtensionContextResult<>(result, replayState, ReplayPolicy.ABOVE_SIZE, thresholdBytes);
    }

    /** Returns the application result. */
    public T result() {
        return result;
    }

    /** Returns the replay-only state. */
    public T replayState() {
        return replayState;
    }

    /** Returns whether children should replay for a serialized full result of the given size. */
    public boolean shouldReplayChildren(int serializedResultBytes) {
        return replayPolicy == ReplayPolicy.ALWAYS
                || replayPolicy == ReplayPolicy.ABOVE_SIZE && serializedResultBytes >= thresholdBytes;
    }
}
