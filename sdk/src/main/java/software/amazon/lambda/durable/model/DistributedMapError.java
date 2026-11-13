// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import java.util.List;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Error details on a distributed map run or one of its items.
 *
 * <p>Held as a record rather than the client's ErrorObject, so it survives serialization across checkpoint-and-replay
 * cycles without requiring AWS SDK-specific Jackson modules.
 */
public record DistributedMapError(String errorType, String errorMessage, String errorData, List<String> stackTrace) {

    /** Creates an error from a throwable. */
    public static DistributedMapError of(Throwable e) {
        // errorData stays null. A throwable carries no machine-readable error data, so there is nothing to put here.
        return new DistributedMapError(
                e.getClass().getName(), e.getMessage(), null, ExceptionHelper.serializeStackTrace(e.getStackTrace()));
    }
}
