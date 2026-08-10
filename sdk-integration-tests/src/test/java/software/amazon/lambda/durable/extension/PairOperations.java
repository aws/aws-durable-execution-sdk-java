// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.ExtensionContext;

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
            leftFuture = left.stepAsync(String.class, () -> "L");
            rightFuture = right.stepAsync(String.class, () -> "R");
        } else {
            rightFuture = right.stepAsync(String.class, () -> "R");
            leftFuture = left.stepAsync(String.class, () -> "L");
        }

        return new PairFuture(leftFuture, rightFuture, pause.waitAsync(Duration.ofSeconds(1)));
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
}
