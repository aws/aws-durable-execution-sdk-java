// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.model.OperationSubType.WAIT;

import java.time.Duration;
import java.util.Objects;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Context-free static facade and canonical implementation of durable WAIT operations. */
public final class DurableWaitOperation {
    private DurableWaitOperation() {}

    public static Void wait(String name, Duration duration) {
        return waitAsync(name, duration).get();
    }

    public static DurableFuture<Void> waitAsync(String name, Duration duration) {
        return waitAsync(ExtensionContext.getCurrentContext(), name, duration);
    }

    public static DurableFuture<Void> waitAsync(ExtensionContext context, String name, Duration duration) {
        Objects.requireNonNull(context, "context cannot be null");
        ParameterValidator.validateOperationName(name);
        ParameterValidator.validateDuration(duration, "Wait duration");
        return context.reserve(name).waitAsync(WAIT.getValue(), duration);
    }
}
