// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.model;

import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.util.ExceptionHelper;

public record DeserializedOperationResult<T>(OperationStatus status, T result, Throwable throwable) {
    public static <T> DeserializedOperationResult<T> succeeded(T result) {
        return new DeserializedOperationResult<>(OperationStatus.SUCCEEDED, result, null);
    }

    public static <T> DeserializedOperationResult<T> failed(Throwable throwable) {
        return new DeserializedOperationResult<>(OperationStatus.FAILED, null, throwable);
    }

    public T get() {
        if (status == OperationStatus.SUCCEEDED) {
            return result;
        }
        ExceptionHelper.sneakyThrow(throwable);
        return null;
    }
}
