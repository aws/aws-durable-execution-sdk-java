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
    void batchSize_one_shouldPass() {
        assertEquals(
                1,
                DistributedMapProcessor.reportBatchOutcome("fn")
                        .batchSize(1)
                        .build()
                        .batchSize());
    }

    @Test
    void batchSize_aboveFormerMaximum_shouldPass() {
        assertEquals(
                10001,
                DistributedMapProcessor.reportBatchOutcome("fn")
                        .batchSize(10001)
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
        assertEquals("batchSize must be at least 1, got: 0", exception.getMessage());
    }

    @Test
    void durableExecutionNamePrefix_set_isStored() {
        assertEquals(
                "a",
                DistributedMapProcessor.reportBatchOutcome("fn")
                        .durableExecutionNamePrefix("a")
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
        assertEquals("durableExecutionNamePrefix cannot be empty", exception.getMessage());
    }

    @Test
    void durableExecutionNamePrefix_over36Characters_isAccepted() {
        var prefix = "a".repeat(37);
        assertEquals(
                prefix,
                DistributedMapProcessor.reportBatchOutcome("fn")
                        .durableExecutionNamePrefix(prefix)
                        .build()
                        .durableExecutionNamePrefix());
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
    void functionName_171And256Characters_areAccepted() {
        for (var length : new int[] {171, 256}) {
            var name = "a".repeat(length);
            assertEquals(
                    name,
                    DistributedMapProcessor.reportBatchOutcome(name).build().functionName());
        }
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
