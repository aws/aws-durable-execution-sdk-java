// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result container for batch concurrent operations (map, parallel).
 *
 * <p>Holds ordered results and errors from a batch of concurrent operations. Each index corresponds to the input item
 * at the same position. Includes the {@link CompletionReason} indicating why the operation completed.
 *
 * <p>When serialized for checkpointing, only results and completionReason are included. Errors are excluded because
 * they contain Throwable objects that are not reliably serializable. On replay from a small-result checkpoint, errors
 * will be an empty list with null entries matching the results size.
 *
 * @param <T> the result type of each item
 */
public class BatchResult<T> {
    @JsonProperty("results")
    private final List<T> results;

    @JsonIgnore
    private final List<Throwable> errors;

    @JsonProperty("completionReason")
    private final CompletionReason completionReason;

    public BatchResult(List<T> results, List<Throwable> errors, CompletionReason completionReason) {
        this.results = results;
        this.errors = errors;
        this.completionReason = completionReason;
    }

    @JsonCreator
    private BatchResult(
            @JsonProperty("results") List<T> results,
            @JsonProperty("completionReason") CompletionReason completionReason) {
        this.results = results != null ? results : Collections.emptyList();
        this.errors = new ArrayList<>(Collections.nCopies(this.results.size(), null));
        this.completionReason = completionReason != null ? completionReason : CompletionReason.ALL_COMPLETED;
    }

    /** Returns an empty BatchResult with no results and no errors. */
    public static <T> BatchResult<T> empty() {
        return new BatchResult<>(Collections.emptyList(), Collections.emptyList(), CompletionReason.ALL_COMPLETED);
    }

    /** Returns the result at the given index, or null if that item failed. */
    public T getResult(int index) {
        return results.get(index);
    }

    /** Returns the error at the given index, or null if that item succeeded. */
    public Throwable getError(int index) {
        return errors.get(index);
    }

    /** Returns true if all items succeeded (no errors). */
    public boolean allSucceeded() {
        return errors.stream().noneMatch(e -> e != null);
    }

    /** Returns the reason the operation completed. */
    public CompletionReason completionReason() {
        return completionReason;
    }

    /** Returns all results as an unmodifiable list. */
    public List<T> results() {
        return Collections.unmodifiableList(results);
    }

    /** Returns all errors as an unmodifiable list. */
    public List<Throwable> errors() {
        return Collections.unmodifiableList(errors);
    }

    /** Returns the number of items in this batch. */
    public int size() {
        return results.size();
    }

    /** Returns results that succeeded (non-null results). */
    public List<T> succeeded() {
        return results.stream().filter(r -> r != null).toList();
    }

    /** Returns errors that occurred (non-null errors). */
    public List<Throwable> failed() {
        return errors.stream().filter(e -> e != null).toList();
    }
}
