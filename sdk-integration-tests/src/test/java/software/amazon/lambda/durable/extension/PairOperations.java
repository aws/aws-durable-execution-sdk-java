// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.OperationSubType;

/** Example extension library implemented only with public SDK contracts. */
public final class PairOperations {
    private PairOperations() {}

    public static DurableFuture<String> pairAsync(String name, AtomicInteger extensionExecutions) {
        var extension = ExtensionContext.getCurrentContext();
        var left = extension.reserve(name + "-left");
        var right = extension.reserve(name + "-right");
        var pause = extension.reserve(name + "-pause");

        DurableFuture<String> leftFuture;
        DurableFuture<String> rightFuture;
        if (extensionExecutions.getAndIncrement() % 2 == 0) {
            leftFuture = stepAsync(left, "L");
            rightFuture = stepAsync(right, "R");
        } else {
            rightFuture = stepAsync(right, "R");
            leftFuture = stepAsync(left, "L");
        }

        return new PairFuture(
                leftFuture, rightFuture, pause.waitAsync(OperationSubType.WAIT.getValue(), Duration.ofSeconds(1)));
    }

    public static DurableFuture<String> customOperationsAsync(String name, AtomicInteger extensionExecutions) {
        var extension = ExtensionContext.getCurrentContext();
        ExtensionOperation step;
        ExtensionOperation wait;
        ExtensionOperation context;
        if (extensionExecutions.getAndIncrement() % 2 == 0) {
            step = extension.reserve(name + "-step", name + "-step-id");
            wait = extension.reserve(name + "-wait", name + "-wait-id");
            context = extension.reserve(name + "-context", name + "-context-id");
        } else {
            context = extension.reserve(name + "-context", name + "-context-id");
            wait = extension.reserve(name + "-wait", name + "-wait-id");
            step = extension.reserve(name + "-step", name + "-step-id");
        }

        var stepFuture = step.stepAsync(
                "AcmeStep",
                TypeToken.get(String.class),
                state -> ExtensionStepResult.succeed("step"),
                ExtensionStepConfig.<String>builder().build());
        var waitFuture = wait.waitAsync("AcmeWait", Duration.ofSeconds(1));
        var contextFuture = context.runInChildContextAsync(
                "AcmeContext",
                TypeToken.get(String.class),
                () -> ExtensionContextResult.completed("context"),
                ExtensionContextConfig.builder().build());
        return new CustomOperationsFuture(stepFuture, waitFuture, contextFuture);
    }

    private static DurableFuture<String> stepAsync(ExtensionOperation operation, String result) {
        return operation.stepAsync(
                OperationSubType.STEP.getValue(),
                TypeToken.get(String.class),
                state -> ExtensionStepResult.succeed(result),
                ExtensionStepConfig.<String>builder().build());
    }

    private record PairFuture(DurableFuture<String> left, DurableFuture<String> right, DurableFuture<Void> pause)
            implements DurableFuture<String> {
        @Override
        public String get() {
            pause.get();
            return left.get() + right.get();
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return CompletableFuture.allOf(left.completionFuture(), right.completionFuture(), pause.completionFuture());
        }
    }

    private record CustomOperationsFuture(
            DurableFuture<String> step, DurableFuture<Void> pause, DurableFuture<String> context)
            implements DurableFuture<String> {
        @Override
        public String get() {
            pause.get();
            return step.get() + ":" + context.get();
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return CompletableFuture.allOf(
                    step.completionFuture(), pause.completionFuture(), context.completionFuture());
        }
    }
}
