// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;

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
        return unwrap(throwable, UnwrapMode.COMPLETION);
    }

    /** Inspects asynchronous wrappers for fatal causes without looping on cyclic or unreadable cause chains. */
    public static Throwable unwrapAsyncFailure(Throwable failure) {
        return unwrap(failure, UnwrapMode.ASYNC);
    }

    /**
     * Removes completion transport wrappers while retaining application ExecutionException values, except when an
     * asynchronous wrapper contains a fatal JVM error. Each cause accessor is read at most once.
     */
    public static Throwable unwrapInvocationFailure(Throwable failure) {
        return unwrap(failure, UnwrapMode.INVOCATION);
    }

    private enum UnwrapMode {
        COMPLETION,
        ASYNC,
        INVOCATION
    }

    @SuppressWarnings("removal")
    private static Throwable unwrap(Throwable failure, UnwrapMode mode) {
        if (!(failure instanceof CompletionException)
                && !(mode != UnwrapMode.COMPLETION && failure instanceof ExecutionException)) return failure;
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable applicationWrapper = null;
        while (failure instanceof CompletionException
                || (mode != UnwrapMode.COMPLETION && failure instanceof ExecutionException)) {
            if (!visited.add(failure)) break;
            if (mode == UnwrapMode.INVOCATION && applicationWrapper == null && failure instanceof ExecutionException) {
                applicationWrapper = failure;
            }
            Throwable cause;
            try {
                cause = failure.getCause();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                // Fatal inspectors return the fatal so their caller can notify the invocation before rethrowing it.
                if (mode == UnwrapMode.COMPLETION) throw fatal;
                return fatal;
            } catch (Throwable unreadableCause) {
                // A malformed diagnostic must not replace the failure that crossed the plugin boundary.
                break;
            }
            if (cause == null) {
                // Preserve the released completion-only helper's cause-less-wrapper behavior.
                if (mode == UnwrapMode.COMPLETION) return null;
                break;
            }
            failure = cause;
        }
        if (mode == UnwrapMode.INVOCATION
                && applicationWrapper != null
                && !(failure instanceof VirtualMachineError)
                && !(failure instanceof ThreadDeath)) {
            return applicationWrapper;
        }
        return failure;
    }

    /**
     * build an ErrorObject from a Throwable
     *
     * @param throwable the Throwable from which to build the errorObject
     * @return the ErrorObject
     */
    public static ErrorObject buildErrorObject(Throwable throwable, SerDes serDes) {
        return buildErrorObject(throwable, serDes.serialize(throwable));
    }

    /** Builds an error using the operation's context-aware serializer. */
    public static ErrorObject buildErrorObject(Throwable throwable, SerDes serDes, SerDesContext context) {
        return buildErrorObject(throwable, serDes.serialize(throwable, context));
    }

    private static ErrorObject buildErrorObject(Throwable throwable, String errorData) {
        return ErrorObject.builder()
                .errorType(throwable.getClass().getName())
                .errorMessage(throwable.getMessage())
                .errorData(errorData)
                .stackTrace(serializeStackTrace(throwable.getStackTrace()))
                .build();
    }

    /**
     * Serializes a stack trace to a list of pipe-delimited strings in the format
     * {@code className|methodName|fileName|lineNumber}.
     *
     * @param stackTrace the stack trace elements to serialize
     * @return list of serialized stack trace strings
     */
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

    /**
     * Deserializes a list of pipe-delimited strings back into stack trace elements.
     *
     * @param stackTrace the serialized stack trace strings
     * @return array of reconstructed StackTraceElements
     */
    public static StackTraceElement[] deserializeStackTrace(List<String> stackTrace) {
        return stackTrace.stream()
                .map((s) -> {
                    String[] tokens = s.split("\\|");
                    return new StackTraceElement(tokens[0], tokens[1], tokens[2], Integer.parseInt(tokens[3]));
                })
                .toArray(StackTraceElement[]::new);
    }
}
