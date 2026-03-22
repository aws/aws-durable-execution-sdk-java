// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import java.util.List;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Error details for a failed map item.
 *
 * <p>Stores error information as plain strings so that {@link MapResult} can be serialized through the user's SerDes
 * without requiring AWS SDK-specific Jackson modules.
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
