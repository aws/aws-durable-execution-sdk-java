// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.exception.ParallelBranchSkippedException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class ParallelBranchRegistrationRaceTest {

    @AfterEach
    void clearRegistrationPause() {
        ParallelOperation.beforeBranchRegistration(null);
    }

    @Test
    void branchRegisteredAfterSnapshotIsSkippedInsteadOfSuspending() {
        var calls = new AtomicInteger();
        ParallelOperation.beforeBranchRegistration(() -> {
            if (calls.incrementAndGet() != 2) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        });

        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var config = ParallelConfig.builder()
                    .completionConfig(CompletionConfig.firstSuccessful())
                    .maxConcurrency(1)
                    .build();
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = context.parallel("first-success-parallel", config);
            try (parallel) {
                futures.add(parallel.branch("branch-1", String.class, ctx -> "success-1"));
                futures.add(parallel.branch("branch-2", String.class, ctx -> "skipped-2"));
            }
            var skipped = assertThrows(
                    ParallelBranchSkippedException.class, () -> futures.get(1).get());
            assertEquals("branch-2", skipped.getBranchName());
            return "done";
        });

        var result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> runner.runUntilComplete("test"));
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("done", result.getResult(String.class));
    }
}
