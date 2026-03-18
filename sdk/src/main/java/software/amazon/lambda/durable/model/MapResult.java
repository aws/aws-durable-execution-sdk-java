// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import java.util.Collections;
import java.util.List;
import software.amazon.awssdk.services.lambda.model.ErrorObject;

/**
 * Result container for map operations.
 *
 * <p>Holds ordered results from a map operation. Each index corresponds to the input item at the same position. Each
 * item is represented as a {@link MapResultItem} containing its status, result, and error. Includes the
 * {@link CompletionReason} indicating why the operation completed.
 *
 * <p>Errors are stored as {@link ErrorObject} rather than raw Throwable, so they survive serialization across
 * checkpoint-and-replay cycles.
 *
 * @param items ordered result items from the map operation
 * @param completionReason why the operation completed
 * @param <T> the result type of each item
 */
public record MapResult<T>(List<MapResultItem<T>> items, CompletionReason completionReason) {

    /** Compact constructor that applies defensive copy and defaults. */
    public MapResult {
        items = items != null ? List.copyOf(items) : Collections.emptyList();
        completionReason = completionReason != null ? completionReason : CompletionReason.ALL_COMPLETED;
    }

    /** Returns an empty MapResult with no items. */
    public static <T> MapResult<T> empty() {
        return new MapResult<>(Collections.emptyList(), CompletionReason.ALL_COMPLETED);
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
    public ErrorObject getError(int index) {
        return items.get(index).error();
    }

    /** Returns true if all items succeeded (no errors). */
    public boolean allSucceeded() {
        return items.stream().noneMatch(item -> item.error() != null);
    }

    /** Returns the number of items in this result. */
    public int size() {
        return items.size();
    }

    /** Returns all results as an unmodifiable list (nulls for failed/not-started items). */
    public List<T> results() {
        return Collections.unmodifiableList(
                items.stream().map(MapResultItem::result).toList());
    }

    /** Returns results that succeeded (non-null results). */
    public List<T> succeeded() {
        return items.stream().map(MapResultItem::result).filter(r -> r != null).toList();
    }

    /** Returns errors that occurred (non-null errors). */
    public List<ErrorObject> failed() {
        return items.stream().map(MapResultItem::error).filter(e -> e != null).toList();
    }
}
