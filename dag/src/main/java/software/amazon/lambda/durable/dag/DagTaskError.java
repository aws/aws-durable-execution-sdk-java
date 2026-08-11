// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Error details for a failed DAG task.
 *
 * <p>Serializes to the cross-language canonical error object shape — PascalCase {@code ErrorType} /
 * {@code ErrorMessage} / {@code StackTrace} (envelope convergence contract), matching the platform's error-object
 * convention and what the JS and Python SDKs already emit. {@code StackTrace} is {@code null} when unavailable. Carries
 * an optional reconstructed {@code cause} which is never serialized.
 *
 * <p><b>Value semantics (recorded cross-language difference):</b> Java's {@code ErrorType} carries the <em>thrown
 * exception's class name</em> (e.g. {@code java.lang.RuntimeException}) — see {@link #of(Throwable)} — whereas JS and
 * Python put the SDK operation error type (e.g. {@code StepError}). Field names converge; these values are
 * language-specific by nature and are documented, not converged, in this change.
 *
 * @param errorType the thrown exception's fully qualified class name (serialized as {@code ErrorType})
 * @param errorMessage the error message (serialized as {@code ErrorMessage})
 * @param stackTrace the stack trace frames, or {@code null} (serialized as {@code StackTrace})
 * @param cause the reconstructed cause, if available (never serialized)
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record DagTaskError(
        @JsonProperty("ErrorType") String errorType,
        @JsonProperty("ErrorMessage") String errorMessage,
        @JsonProperty("StackTrace") List<String> stackTrace,
        @JsonIgnore Optional<Throwable> cause) {

    /** Jackson entry point — reconstructs without a cause, from the PascalCase canonical shape. */
    @JsonCreator
    public DagTaskError(
            @JsonProperty("ErrorType") String errorType,
            @JsonProperty("ErrorMessage") String errorMessage,
            @JsonProperty("StackTrace") List<String> stackTrace) {
        this(errorType, errorMessage, stackTrace, Optional.empty());
    }

    /** Builds a {@code DagTaskError} from a throwable, retaining it as the (non-serialized) cause. */
    public static DagTaskError of(Throwable e) {
        return new DagTaskError(
                e.getClass().getName(),
                e.getMessage(),
                ExceptionHelper.serializeStackTrace(e.getStackTrace()),
                Optional.of(e));
    }
}
