// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.util.Objects;
import java.util.function.BiConsumer;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.context.extension.WaitForCallbackExtension;
import software.amazon.lambda.durable.extension.ExtensionContext;

/** Context-free static facades for durable wait-for-callback operations. */
public final class DurableWaitForCallbackOperations {
    private DurableWaitForCallbackOperations() {}

    public static <T> T waitForCallback(String name, Class<T> resultType, Runnable submitter) {
        return waitForCallbackAsync(name, resultType, submitter).get();
    }

    public static <T> T waitForCallback(String name, TypeToken<T> resultType, Runnable submitter) {
        return waitForCallbackAsync(name, resultType, submitter).get();
    }

    public static <T> T waitForCallback(
            String name, Class<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return waitForCallbackAsync(name, resultType, submitter, config).get();
    }

    public static <T> T waitForCallback(
            String name, TypeToken<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return waitForCallbackAsync(name, resultType, submitter, config).get();
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(String name, Class<T> resultType, Runnable submitter) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), submitter);
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(String name, TypeToken<T> resultType, Runnable submitter) {
        return waitForCallbackAsync(
                name, resultType, submitter, WaitForCallbackConfig.builder().build());
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(
            String name, Class<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), submitter, config);
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(
            String name, TypeToken<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return WaitForCallbackExtension.execute(currentContext(), name, resultType, adapt(submitter), config);
    }

    private static BiConsumer<String, StepContext> adapt(Runnable submitter) {
        Objects.requireNonNull(submitter, "submitter cannot be null");
        return (callbackId, ignored) -> {
            try (var scope = WaitForCallbackContext.attach(callbackId)) {
                submitter.run();
            }
        };
    }

    private static ExtensionContext currentContext() {
        return ExtensionContext.getCurrentContext();
    }
}
