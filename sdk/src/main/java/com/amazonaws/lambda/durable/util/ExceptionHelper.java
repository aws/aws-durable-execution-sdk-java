// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.util;

import com.amazonaws.lambda.durable.serde.SerDes;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionException;
import software.amazon.awssdk.services.lambda.model.ErrorObject;

/** Utility class for handling exceptions */
public class ExceptionHelper {
    /**
     * Throws any exception as if it were unchecked using type erasure. This preserves the original exception type and
     * stack trace.
     *
     * @param exception the exception to throw
     * @param <T> the exception type (erased at runtime)
     * @throws T the exception as an unchecked exception
     */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> void sneakyThrow(Throwable exception) throws T {
        throw (T) exception;
    }

    /**
     * unwrap the exception that is wrapped by CompletionException
     *
     * @param throwable the throwable to unwrap
     * @return the original Throwable that is not a CompletionException
     */
    public static Throwable unwrapCompletableFuture(Throwable throwable) {
        while (throwable instanceof CompletionException) {
            throwable = throwable.getCause();
        }
        return throwable;
    }

    /**
     * build an ErrorObject from a Throwable
     *
     * @param throwable the Throwable from which to build the errorObject
     * @return the ErrorObject
     */
    public static ErrorObject buildErrorObject(Throwable throwable, SerDes serDes) {
        return ErrorObject.builder()
                .errorType(throwable.getClass().getName())
                .errorMessage(throwable.getMessage())
                .errorData(serDes.serialize(throwable))
                .stackTrace(serializeStackTrace(throwable.getStackTrace()))
                .build();
    }

    // StackTraceElement.toString() is implementation-dependent, so we'll define our
    // own format.
    public static List<String> serializeStackTrace(StackTraceElement[] stackTrace) {
        return Arrays.stream(stackTrace)
                .map((element) -> String.format(
                        "%s|%s|%s|%d",
                        element.getClassName(),
                        element.getMethodName(),
                        element.getFileName(),
                        element.getLineNumber()))
                .toList();
    }

    public static StackTraceElement[] deserializeStackTrace(List<String> stackTrace) {
        return stackTrace.stream()
                .map((s) -> {
                    String[] tokens = s.split("\\|");
                    return new StackTraceElement(tokens[0], tokens[1], tokens[2], Integer.parseInt(tokens[3]));
                })
                .toArray(StackTraceElement[]::new);
    }
}
