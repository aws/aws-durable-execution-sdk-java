// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Thrown when a task's {@code runIf} predicate throws. A {@code runIf} predicate is specified as a <b>synchronous,
 * deterministic, pure</b> function of resolved upstream results, re-evaluated on every replay and never checkpointed. A
 * throw is therefore a <b>defect in deterministic code</b>, not a business outcome: the scheduler <b>aborts</b> the DAG
 * (it starts no further tasks and the offending task is left with <i>no</i> terminal state — neither {@code FAILED} nor
 * {@code SKIPPED}) and the {@code dag(...)} operation fails with this exception rather than recording a task failure
 * that would drive {@code ALL_FAILED}/{@code ANY_FAILED}/{@code ALL_DONE} compensation paths.
 *
 * <p>The {@linkplain #getMessage() message} names the offending task and the {@linkplain #getCause() cause} is the
 * original error thrown by the predicate, with its stack trace preserved.
 *
 * <p><b>Child-context boundary.</b> A DAG runs inside a {@code runInChildContext} node. When this exception crosses
 * that boundary it is checkpointed and reconstructed from its serialized form; the {@code dag(...)} caller observes a
 * {@code DagPredicateException} whose message names the task, whose {@link #taskName()} is preserved, and whose cause
 * is the reconstructed original error. See {@code docs/core/dag.md}.
 *
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public class DagPredicateException extends DagException {

    private final String taskName;

    /**
     * Primary constructor used by the scheduler.
     *
     * @param taskName the name of the task whose {@code runIf} predicate threw
     * @param cause the original error thrown by the predicate
     */
    public DagPredicateException(String taskName, Throwable cause) {
        super(buildMessage(taskName, cause), cause);
        this.taskName = taskName;
    }

    /**
     * Reconstruction constructor used when the exception is deserialized after crossing the child-context boundary. It
     * takes the already-built message and the reconstructed cause directly (setting the cause at construction avoids
     * {@code Throwable.initCause}, which the base hierarchy would otherwise reject because it pre-initializes the cause
     * to {@code null}). Kept private; selected by Jackson via {@code @JsonCreator}.
     */
    @JsonCreator
    private DagPredicateException(
            @JsonProperty("message") String message,
            @JsonProperty("cause") Throwable cause,
            @JsonProperty("taskName") String taskName) {
        super(message, cause);
        this.taskName = taskName;
    }

    /** The name of the task whose {@code runIf} predicate threw. */
    @JsonProperty("taskName")
    public String taskName() {
        return taskName;
    }

    private static String buildMessage(String taskName, Throwable cause) {
        StringBuilder sb = new StringBuilder("runIf predicate for DAG task '")
                .append(taskName)
                .append("' threw ");
        if (cause == null) {
            sb.append("null");
        } else {
            sb.append(cause.getClass().getName());
            if (cause.getMessage() != null) {
                sb.append(": ").append(cause.getMessage());
            }
        }
        return sb.toString();
    }
}
