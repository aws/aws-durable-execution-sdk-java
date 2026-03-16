// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/**
 * Represents the outcome of a single item in a map operation.
 *
 * <p>Each item either succeeds with a result or fails with an error. The status field indicates which case applies.
 *
 * <p>When serialized for checkpointing, only status and result are included. The error field is transient because
 * Throwable objects are not reliably serializable across different serializers. On replay from a small-result
 * checkpoint, errors will be null; on replay from a large-result checkpoint (replayChildren), errors are reconstructed
 * from individual child context checkpoints.
 *
 * @param <T> the result type
 */
public class MapResultItem<T> {

    /** Status of an individual map item. */
    public enum Status {
        SUCCEEDED,
        FAILED
    }

    private Status status;
    private T result;
    private transient Throwable error;

    /** Default constructor for deserialization. */
    public MapResultItem() {}

    private MapResultItem(Status status, T result, Throwable error) {
        this.status = status;
        this.result = result;
        this.error = error;
    }

    /** Creates a successful result item. */
    public static <T> MapResultItem<T> success(T result) {
        return new MapResultItem<>(Status.SUCCEEDED, result, null);
    }

    /** Creates a failed result item. */
    public static <T> MapResultItem<T> failure(Throwable error) {
        return new MapResultItem<>(Status.FAILED, null, error);
    }

    /** Creates an empty (not started) result item. */
    public static <T> MapResultItem<T> notStarted() {
        return new MapResultItem<>(null, null, null);
    }

    /** Returns the status of this item, or null if the item was never started. */
    public Status getStatus() {
        return status;
    }

    /** Returns the result, or null if the item failed or was not started. */
    public T getResult() {
        return result;
    }

    /** Returns the error, or null if the item succeeded or was not started. */
    public Throwable getError() {
        return error;
    }

    // Convenience accessors matching the original API style

    /** Returns the status of this item, or null if the item was never started. */
    public Status status() {
        return status;
    }

    /** Returns the result, or null if the item failed or was not started. */
    public T result() {
        return result;
    }

    /** Returns the error, or null if the item succeeded or was not started. */
    public Throwable error() {
        return error;
    }
}
