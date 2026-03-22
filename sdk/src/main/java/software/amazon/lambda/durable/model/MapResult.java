// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import java.util.Collections;
import java.util.List;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Result container for map operations.
 *
 * <p>Holds ordered results from a map operation. Each index corresponds to the input item at the same position. Each
 * item is represented as a {@link MapResultItem} containing its status, result, and error. Includes the
 * {@link ConcurrencyCompletionStatus} indicating why the operation completed.
 *
 * <p>Errors are stored as {@link MapError} rather than raw Throwable, so they survive serialization across
 * checkpoint-and-replay cycles without requiring AWS SDK-specific Jackson modules.
 *
 * @param items ordered result items from the map operation
 * @param completionReason why the operation completed
 * @param <T> the result type of each item
 */
public record MapResult<T>(List<MapResultItem<T>> items, ConcurrencyCompletionStatus completionReason) {

    /** Compact constructor that applies defensive copy and defaults. */
    public MapResult {
        items = items != null ? List.copyOf(items) : Collections.emptyList();
        completionReason = completionReason != null ? completionReason : ConcurrencyCompletionStatus.ALL_COMPLETED;
    }

    /** Returns an empty MapResult with no items. */
    public static <T> MapResult<T> empty() {
        return new MapResult<>(Collections.emptyList(), ConcurrencyCompletionStatus.ALL_COMPLETED);
    }

    /** Returns the result item at the given index. */
    public MapResultItem<T> getItem(int index) {
        return items.get(index);
    }

    /** Returns the result at the given index, or null if that item failed or was not started. */
    public T getResult(int index) {
        return items.get(index).result();
    }

    /** Returns the error at the given index, or null if that item succeeded or was not started. */
    public MapError getError(int index) {
        return items.get(index).error();
    }

    /** Returns true if all items succeeded (no failures or not-started items). */
    public boolean allSucceeded() {
        return items.stream().allMatch(item -> item.status() == MapResultItem.Status.SUCCEEDED);
    }

    /** Returns the number of items in this result. */
    public int size() {
        return items.size();
    }

    /** Returns all results as an unmodifiable list (nulls for failed/not-started items). */
    public List<T> results() {
        return items.stream().map(MapResultItem::result).toList();
    }

    /** Returns results from items that succeeded (includes null results from successful items). */
    public List<T> succeeded() {
        return items.stream()
                .filter(item -> item.status() == MapResultItem.Status.SUCCEEDED)
                .map(MapResultItem::result)
                .toList();
    }

    /** Returns errors from items that failed. */
    public List<MapError> failed() {
        return items.stream()
                .filter(item -> item.status() == MapResultItem.Status.FAILED)
                .map(MapResultItem::error)
                .toList();
    }

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
            SKIPPED
        }

        /** Creates a successful result item. */
        public static <T> MapResultItem<T> succeeded(T result) {
            return new MapResultItem<>(Status.SUCCEEDED, result, null);
        }

        /** Creates a failed result item. */
        public static <T> MapResultItem<T> failed(MapError error) {
            return new MapResultItem<>(Status.FAILED, null, error);
        }

        /** Creates a skipped result item. */
        public static <T> MapResultItem<T> skipped() {
            return new MapResultItem<>(Status.SKIPPED, null, null);
        }
    }

    /**
     * Error details for a failed map item.
     *
     * <p>Stores error information as plain strings so that {@link MapResult} can be serialized through the user's
     * SerDes without requiring AWS SDK-specific Jackson modules.
     *
     * @param errorType the fully qualified exception class name
     * @param errorMessage the error message
     * @param stackTrace the stack trace frames, or null
     */
    public record MapError(String errorType, String errorMessage, List<String> stackTrace) {
        public static MapError of(Throwable e) {
            return new MapError(
                    e.getClass().getName(), e.getMessage(), ExceptionHelper.serializeStackTrace(e.getStackTrace()));
        }
    }
}
