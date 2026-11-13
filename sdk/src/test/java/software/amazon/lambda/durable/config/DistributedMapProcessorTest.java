// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DistributedMapProcessorTest {

    @Test
    void batch_setsBatchMode() {
        var processor = DistributedMapProcessor.reportBatchOutcome("fn").build();

        assertEquals("fn", processor.functionName());
        assertEquals(DistributedMapProcessor.ResponseMode.BATCH, processor.responseMode());
    }

    @Test
    void itemOutcomes_setsItemOutcomesMode() {
        var processor = DistributedMapProcessor.reportItemOutcomes("fn").build();

        assertEquals(DistributedMapProcessor.ResponseMode.ITEM_OUTCOMES, processor.responseMode());
    }

    @Test
    void build_defaultsAreNull() {
        var processor = DistributedMapProcessor.reportBatchOutcome("fn").build();

        assertNull(processor.batchSize());
        assertNull(processor.retry());
        assertNull(processor.durableExecutionNamePrefix());
    }

    @Test
    void batchSize_atBoundaries_shouldPass() {
        assertEquals(
                1,
                DistributedMapProcessor.reportBatchOutcome("fn")
                        .batchSize(1)
                        .build()
                        .batchSize());
        assertEquals(
                10000,
                DistributedMapProcessor.reportBatchOutcome("fn")
                        .batchSize(10000)
                        .build()
                        .batchSize());
    }

    @Test
    void batchSize_belowMinimum_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessor.reportBatchOutcome("fn")
                        .batchSize(0)
                        .build());
        assertEquals("batchSize must be between 1 and 10000, got: 0", exception.getMessage());
    }

    @Test
    void batchSize_aboveMaximum_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessor.reportBatchOutcome("fn")
                        .batchSize(10001)
                        .build());
        assertEquals("batchSize must be between 1 and 10000, got: 10001", exception.getMessage());
    }

    @Test
    void durableExecutionNamePrefix_atBoundaries_shouldPass() {
        assertEquals(
                "a",
                DistributedMapProcessor.reportBatchOutcome("fn")
                        .durableExecutionNamePrefix("a")
                        .build()
                        .durableExecutionNamePrefix());
        var thirtySix = "a".repeat(36);
        assertEquals(
                thirtySix,
                DistributedMapProcessor.reportBatchOutcome("fn")
                        .durableExecutionNamePrefix(thirtySix)
                        .build()
                        .durableExecutionNamePrefix());
    }

    @Test
    void durableExecutionNamePrefix_empty_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessor.reportBatchOutcome("fn")
                        .durableExecutionNamePrefix("")
                        .build());
        assertEquals("durableExecutionNamePrefix must be between 1 and 36 characters, got: 0", exception.getMessage());
    }

    @Test
    void durableExecutionNamePrefix_tooLong_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessor.reportBatchOutcome("fn")
                        .durableExecutionNamePrefix("a".repeat(37))
                        .build());
        assertEquals("durableExecutionNamePrefix must be between 1 and 36 characters, got: 37", exception.getMessage());
    }

    @Test
    void retryFields_areStored() {
        var processor = DistributedMapProcessor.reportBatchOutcome("fn")
                .retry(DistributedMapProcessorRetryConfig.builder()
                        .maxRetryAttempts(3)
                        .maxRetryDuration(Duration.ofMinutes(5))
                        .build())
                .build();

        assertEquals(3, processor.retry().maxRetryAttempts());
        assertEquals(Duration.ofMinutes(5), processor.retry().maxRetryDuration());
    }

    @Test
    void unlimitedRetryAttempts_isStored() {
        var processor = DistributedMapProcessor.reportBatchOutcome("fn")
                .retry(DistributedMapProcessorRetryConfig.builder()
                        .maxRetryAttempts(DistributedMapProcessorRetryConfig.UNLIMITED)
                        .build())
                .build();

        assertEquals(
                DistributedMapProcessorRetryConfig.UNLIMITED, processor.retry().maxRetryAttempts());
    }

    @Test
    void functionName_empty_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessor.reportBatchOutcome("").build());
        assertEquals("function name cannot be empty", exception.getMessage());
    }

    @Test
    void functionName_null_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessor.reportBatchOutcome(null).build());
        assertEquals("function name cannot be empty", exception.getMessage());
    }

    @Test
    void functionName_tooLong_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> DistributedMapProcessor.reportBatchOutcome("a".repeat(171))
                        .build());
        assertEquals("function name must be at most 170 characters, got: 171", exception.getMessage());
    }

    @Test
    void functionName_atMaxLength_shouldPass() {
        var name = "a".repeat(170);
        assertEquals(
                name, DistributedMapProcessor.reportBatchOutcome(name).build().functionName());
    }

    @Test
    void build_allFieldsSet() {
        var processor = DistributedMapProcessor.reportItemOutcomes("fn")
                .batchSize(50)
                .durableExecutionNamePrefix("job")
                .retry(DistributedMapProcessorRetryConfig.builder()
                        .maxRetryDuration(Duration.ofMinutes(5))
                        .build())
                .build();

        assertEquals("fn", processor.functionName());
        assertEquals(50, processor.batchSize());
        assertEquals("job", processor.durableExecutionNamePrefix());
        assertEquals(Duration.ofMinutes(5), processor.retry().maxRetryDuration());
    }
}
