// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import java.util.Collections;
import java.util.List;

/**
 * Result container for map operations.
 *
 * <p>Holds ordered results from a map operation. Each index corresponds to the input item at the same position. Each
 * item is represented as a {@link MapResultItem} containing its status, result, and error. Includes the
 * {@link CompletionReason} indicating why the operation completed.
 *
 * <p>When serialized for checkpointing, only status and result fields of each item are included. Error fields are
 * transient because Throwable objects are not reliably serializable. On replay from a small-result checkpoint, errors
 * will be null; on replay from a large-result checkpoint (replayChildren), errors are reconstructed from individual
 * child context checkpoints.
 *
 * @param <T> the result type of each item
 */
public class MapResult<T> {

    private List<MapResultItem<T>> items;
    private CompletionReason completionReason;

    /** Default constructor for deserialization. */
    public MapResult() {}

    public MapResult(List<MapResultItem<T>> items, CompletionReason completionReason) {
        this.items = items != null ? List.copyOf(items) : Collections.emptyList();
        this.completionReason = completionReason != null ? completionReason : CompletionReason.ALL_COMPLETED;
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
    public Throwable getError(int index) {
        return items.get(index).error();
    }

    /** Returns true if all items succeeded (no errors). */
    public boolean allSucceeded() {
        return items.stream().noneMatch(item -> item.error() != null);
    }

    /** Returns the reason the operation completed. */
    public CompletionReason getCompletionReason() {
        return completionReason;
    }

    /** Returns all result items as an unmodifiable list. */
    public List<MapResultItem<T>> getItems() {
        return items;
    }

    /** Returns the number of items in this result. */
    public int size() {
        return items.size();
    }

    // Convenience accessors matching the original API style

    /** Returns the reason the operation completed. */
    public CompletionReason completionReason() {
        return completionReason;
    }

    /** Returns all result items as an unmodifiable list. */
    public List<MapResultItem<T>> items() {
        return items;
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
    public List<Throwable> failed() {
        return items.stream().map(MapResultItem::error).filter(e -> e != null).toList();
    }
}
