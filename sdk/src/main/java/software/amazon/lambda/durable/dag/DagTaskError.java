// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Error details for a failed DAG task.
 *
 * <p>Mirrors the base SDK's {@code MapResult.MapError} shape ({@code errorType}/{@code errorMessage}/{@code stackTrace}
 * as plain strings) so it survives serialization through the user's SerDes without AWS SDK-specific Jackson modules.
 * Carries an optional reconstructed {@code cause} which is not serialized.
 *
 * @param errorType the fully qualified exception class name
 * @param errorMessage the error message
 * @param stackTrace the stack trace frames, or {@code null}
 * @param cause the reconstructed cause, if available (never serialized)
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record DagTaskError(
        String errorType,
        String errorMessage,
        List<String> stackTrace,
        @JsonIgnore Optional<Throwable> cause) {

    /** Jackson entry point — reconstructs without a cause. */
    @JsonCreator
    public DagTaskError(
            @JsonProperty("errorType") String errorType,
            @JsonProperty("errorMessage") String errorMessage,
            @JsonProperty("stackTrace") List<String> stackTrace) {
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
