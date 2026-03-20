// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

/**
 * Represents the outcome of a single item in a map operation.
 *
 * <p>Each item either succeeds with a result, fails with an error, or was never started. The status field indicates
 * which case applies.
 *
 * <p>Errors are stored as {@link MapError} (plain strings) rather than raw Throwable, so they survive serialization
 * across checkpoint-and-replay cycles without requiring AWS SDK-specific Jackson modules.
 *
 * @param status the status of this item
 * @param result the result value, or null if failed/not started
 * @param error the error details, or null if succeeded/not started
 * @param <T> the result type
 */
public record MapResultItem<T>(Status status, T result, MapError error) {

    /** Status of an individual map item. */
    public enum Status {
        SUCCEEDED,
        FAILED,
        NOT_STARTED
    }

    /** Creates a successful result item. */
    public static <T> MapResultItem<T> success(T result) {
        return new MapResultItem<>(Status.SUCCEEDED, result, null);
    }

    /** Creates a failed result item. */
    public static <T> MapResultItem<T> failure(MapError error) {
        return new MapResultItem<>(Status.FAILED, null, error);
    }

    /** Creates a not-started result item. */
    public static <T> MapResultItem<T> notStarted() {
        return new MapResultItem<>(Status.NOT_STARTED, null, null);
    }
}
