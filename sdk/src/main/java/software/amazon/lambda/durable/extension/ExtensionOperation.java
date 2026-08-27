// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import software.amazon.lambda.durable.TypeToken;

/**
 * An opaque, one-shot reservation for a primitive operation.
 *
 * <p>The SDK allocates the operation ID when the reservation is created. Reserving operations in deterministic order
 * allows an extension to launch them later in a different order without changing their IDs.
 *
 * <p>Primitive results are exposed as {@link CompletionStage} values so extension frameworks can compose or suspend on
 * them without retaining a platform thread. A callback reservation returns its ID immediately and exposes its eventual
 * value through {@link ExtensionCallback#result()}.
 */
public interface ExtensionOperation {
    /** Starts a stateful extension step and returns its checkpointed result. */
    <T> CompletionStage<T> stepAsync(
            String subType, TypeToken<T> resultType, ExtensionStepFunction<T> function, ExtensionStepConfig<T> config);

    /** Starts a durable wait and completes after the configured duration. */
    CompletionStage<Void> waitAsync(String subType, Duration duration);

    /** Starts a chained Lambda invocation and returns its checkpointed result. */
    <T, U> CompletionStage<T> invokeAsync(
            String subType, String functionName, U payload, TypeToken<T> resultType, ExtensionInvokeConfig config);

    /** Creates a callback whose ID is available immediately and whose value completes asynchronously. */
    <T> ExtensionCallback<T> createCallback(String subType, TypeToken<T> resultType, ExtensionCallbackConfig config);

    /** Runs extension framework logic in an isolated durable child context. */
    <T> CompletionStage<T> runInChildContextAsync(
            String subType,
            TypeToken<T> resultType,
            ExtensionContextFunction<T> function,
            ExtensionContextConfig config);
}
