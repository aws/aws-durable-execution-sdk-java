// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;

class ParallelConfigTest {

    @Test
    void defaultValues() {
        var config = ParallelConfig.builder().build();

        assertEquals(Integer.MAX_VALUE, config.maxConcurrency());
        assertEquals(CompletionConfig.allCompleted(), config.completionConfig());
    }

    @Test
    void builderRoundTrip() {
        CompletionConfig completionConfig = CompletionConfig.allSuccessful();
        var config = ParallelConfig.builder()
                .maxConcurrency(4)
                .completionConfig(completionConfig)
                .build();

        assertEquals(4, config.maxConcurrency());
        assertEquals(completionConfig, config.completionConfig());
    }

    @Test
    void maxConcurrencyOfOne() {
        var config = ParallelConfig.builder().maxConcurrency(1).build();

        assertEquals(1, config.maxConcurrency());
    }

    @Test
    void invalidConcurrency() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ParallelConfig.builder().maxConcurrency(-1).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ParallelConfig.builder().maxConcurrency(0).build());
    }

    @Test
    void customShouldCompleteAllowed() {
        var completionConfig = CompletionConfig.shouldComplete(status -> status.completedCount() >= 1
                ? CompletionConfig.CompletionDecision.complete(ConcurrencyCompletionStatus.CUSTOM_COMPLETION_SUCCEEDED)
                : CompletionConfig.CompletionDecision.continueExecution());

        var config = ParallelConfig.builder().completionConfig(completionConfig).build();

        assertEquals(completionConfig, config.completionConfig());
    }
}
