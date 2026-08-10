// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.time.Duration;
import software.amazon.lambda.durable.DurableCallbackFuture;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;

/**
 * An opaque, one-shot reservation for a primitive operation.
 *
 * <p>The SDK allocates the operation ID when the reservation is created. Reserving operations in deterministic order
 * allows an extension to launch them later in a different order without changing their IDs.
 */
public interface ExtensionOperation {
    <T> DurableFuture<T> stepAsync(
            String subType, TypeToken<T> resultType, ExtensionStepFunction<T> function, ExtensionStepConfig<T> config);

    DurableFuture<Void> waitAsync(String subType, Duration duration);

    <T, U> DurableFuture<T> invokeAsync(
            String subType, String functionName, U payload, TypeToken<T> resultType, ExtensionInvokeConfig config);

    <T> DurableCallbackFuture<T> createCallback(
            String subType, TypeToken<T> resultType, ExtensionCallbackConfig config);

    <T> DurableFuture<T> runInChildContextAsync(
            String subType,
            TypeToken<T> resultType,
            ExtensionContextFunction<T> function,
            ExtensionContextConfig config);
}
