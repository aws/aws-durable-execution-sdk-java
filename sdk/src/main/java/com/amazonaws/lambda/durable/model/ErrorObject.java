package com.amazonaws.lambda.durable.model;

import java.util.Arrays;
import java.util.List;

public record ErrorObject(
    String errorType,
    String errorMessage,
    List<String> stackTrace
) {
    public static ErrorObject fromException(Throwable e) {
        return new ErrorObject(
            e.getClass().getSimpleName(),
            e.getMessage(),
            Arrays.stream(e.getStackTrace())
                .map(StackTraceElement::toString)
                .limit(50)
                .toList()
        );
    }
}
