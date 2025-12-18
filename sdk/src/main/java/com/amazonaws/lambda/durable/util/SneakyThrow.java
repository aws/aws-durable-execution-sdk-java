package com.amazonaws.lambda.durable.util;

/**
 * Utility class for throwing checked exceptions as unchecked exceptions using type erasure.
 * This allows us to rethrow exceptions without wrapping them in RuntimeException.
 */
public class SneakyThrow {
    /**
     * Throws any exception as if it were unchecked using type erasure.
     * This preserves the original exception type and stack trace.
     * 
     * @param exception the exception to throw
     * @param <T> the exception type (erased at runtime)
     * @throws T the exception as an unchecked exception
     */
    @SuppressWarnings("unchecked")
    public static <T extends Throwable> void sneakyThrow(Throwable exception) throws T {
        throw (T) exception;
    }
}
