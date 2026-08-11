// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thrown by {@code DagResult.throwIfError()} when the DAG completed with at least one failed task. Wraps the first
 * failed task's cause (when available).
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public class DagExecutionException extends DagException {
    public DagExecutionException(String message) {
        super(message);
    }

    /**
     * Primary constructor: wraps the first failed task's cause.
     *
     * <p>Annotated as the {@code @JsonCreator} so that when this exception crosses a {@code runInChildContext} boundary
     * (a nested DAG task that calls {@code throwIfError()} in its body) it is reconstructed with its cause set at
     * construction time. This avoids {@code Throwable.initCause}, which the base hierarchy would otherwise reject
     * because it pre-initializes the cause to {@code null} — the same idiom {@link DagPredicateException} uses. Without
     * it, a cause-carrying {@code DagExecutionException} degrades to a bare {@code ChildContextFailedException} at the
     * caller. Jackson passes {@code cause == null} for the no-cause form, which is equivalent to the single-arg
     * constructor.
     */
    @JsonCreator
    public DagExecutionException(@JsonProperty("message") String message, @JsonProperty("cause") Throwable cause) {
        super(message, cause);
    }
}
