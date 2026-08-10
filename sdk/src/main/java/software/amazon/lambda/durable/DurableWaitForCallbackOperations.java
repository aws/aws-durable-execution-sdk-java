// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.Objects;
import java.util.function.BiConsumer;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;

/** Context-free static facades for durable wait-for-callback operations. */
public final class DurableWaitForCallbackOperations {
    private DurableWaitForCallbackOperations() {}

    public static <T> T waitForCallback(String name, Class<T> resultType, Runnable submitter) {
        return currentContext().waitForCallback(name, resultType, adapt(submitter));
    }

    public static <T> T waitForCallback(String name, TypeToken<T> resultType, Runnable submitter) {
        return currentContext().waitForCallback(name, resultType, adapt(submitter));
    }

    public static <T> T waitForCallback(
            String name, Class<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return currentContext().waitForCallback(name, resultType, adapt(submitter), config);
    }

    public static <T> T waitForCallback(
            String name, TypeToken<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return currentContext().waitForCallback(name, resultType, adapt(submitter), config);
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(
            String name, Class<T> resultType, Runnable submitter) {
        return currentContext().waitForCallbackAsync(name, resultType, adapt(submitter));
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(
            String name, TypeToken<T> resultType, Runnable submitter) {
        return currentContext().waitForCallbackAsync(name, resultType, adapt(submitter));
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(
            String name, Class<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return currentContext().waitForCallbackAsync(name, resultType, adapt(submitter), config);
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(
            String name, TypeToken<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return currentContext().waitForCallbackAsync(name, resultType, adapt(submitter), config);
    }

    private static BiConsumer<String, StepContext> adapt(Runnable submitter) {
        Objects.requireNonNull(submitter, "submitter cannot be null");
        return (callbackId, ignored) -> {
            try (var scope = WaitForCallbackContext.attach(callbackId)) {
                submitter.run();
            }
        };
    }

    private static DurableContext currentContext() {
        return DurableContext.getCurrentContext();
    }
}
