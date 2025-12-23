package com.amazonaws.lambda.durable.exception;

import java.util.Arrays;
import java.util.List;

public class DurableExecutionException extends RuntimeException {
    public DurableExecutionException(String message, Throwable cause, StackTraceElement[] stackTrace) {
        super(message, cause);
        this.setStackTrace(stackTrace);
    }

    public DurableExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    public DurableExecutionException(String message) {
        super(message);
    }

    // StackTraceElement.toString() is implementation-dependent, so we'll define our
    // own format.
    public static List<String> serializeStackTrace(StackTraceElement[] stackTrace) {
        return Arrays.stream(stackTrace)
                .map((element) -> String.format("%s|%s|%s|%d",
                        element.getClassName(),
                        element.getMethodName(),
                        element.getFileName(),
                        element.getLineNumber()))
                .toList();
    }

    public static StackTraceElement[] deserializeStackTrace(List<String> stackTrace) {
        return stackTrace.stream().map((s) -> {
            String[] tokens = s.split("\\|");
            return new StackTraceElement(tokens[0], tokens[1], tokens[2], Integer.parseInt(tokens[3]));
        }).toArray(StackTraceElement[]::new);
    }
}
