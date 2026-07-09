// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Function;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;

class CompletionConfigTest {

    @Test
    void allSuccessful_zeroFailuresTolerated() {
        var config = CompletionConfig.allSuccessful();

        assertNull(config.minSuccessful());
        assertEquals(0, config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
        assertFalse(config.hasCustomShouldComplete());
    }

    @Test
    void allCompleted_allFieldsNull() {
        var config = CompletionConfig.allCompleted();

        assertNull(config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
        assertFalse(config.hasCustomShouldComplete());
    }

    @Test
    void firstSuccessful_minSuccessfulIsOne() {
        var config = CompletionConfig.firstSuccessful();

        assertEquals(1, config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
        assertFalse(config.hasCustomShouldComplete());
    }

    @Test
    void minSuccessful_setsCount() {
        var config = CompletionConfig.minSuccessful(5);

        assertEquals(5, config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void toleratedFailureCount_setsCount() {
        var config = CompletionConfig.toleratedFailureCount(3);

        assertNull(config.minSuccessful());
        assertEquals(3, config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
    }

    @Test
    void toleratedFailurePercentage_setsPercentage() {
        var config = CompletionConfig.toleratedFailurePercentage(0.25);

        assertNull(config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertEquals(0.25, config.toleratedFailurePercentage());
    }

    @Test
    void minSuccessful_withZero_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> CompletionConfig.minSuccessful(0));
        assertEquals("minSuccessful must be at least 1, got: 0", exception.getMessage());
    }

    @Test
    void minSuccessful_withNegative_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> CompletionConfig.minSuccessful(-1));
        assertEquals("minSuccessful must be at least 1, got: -1", exception.getMessage());
    }

    @Test
    void toleratedFailureCount_withNegative_shouldThrow() {
        var exception = assertThrows(IllegalArgumentException.class, () -> CompletionConfig.toleratedFailureCount(-1));
        assertEquals("toleratedFailureCount must be non-negative, got: -1", exception.getMessage());
    }

    @Test
    void toleratedFailurePercentage_withNegative_shouldThrow() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> CompletionConfig.toleratedFailurePercentage(-0.1));
        assertEquals("toleratedFailurePercentage must be between 0.0 and 1.0, got: -0.1", exception.getMessage());
    }

    @Test
    void toleratedFailurePercentage_aboveOne_shouldThrow() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> CompletionConfig.toleratedFailurePercentage(1.5));
        assertEquals("toleratedFailurePercentage must be between 0.0 and 1.0, got: 1.5", exception.getMessage());
    }

    @Test
    void toleratedFailurePercentage_atBoundaries_shouldPass() {
        assertDoesNotThrow(() -> CompletionConfig.toleratedFailurePercentage(0.0));
        assertDoesNotThrow(() -> CompletionConfig.toleratedFailurePercentage(1.0));
    }

    @Test
    void toleratedFailureCount_withZero_shouldPass() {
        var config = CompletionConfig.toleratedFailureCount(0);
        assertEquals(0, config.toleratedFailureCount());
    }

    @Test
    void allCompleted_shouldCompleteReturnsAllCompletedDecision() {
        var config = CompletionConfig.allCompleted();

        var continueDecision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(1, 0, 1, 2, true));
        var completeDecision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(1, 1, 2, 2, true));

        assertFalse(continueDecision.shouldComplete());
        assertTrue(completeDecision.shouldComplete());
        assertTrue(completeDecision.isSucceeded());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, completeDecision.completionStatus());
    }

    @Test
    void minSuccessful_shouldCompleteReturnsMinSuccessfulDecision() {
        var config = CompletionConfig.minSuccessful(2);

        var decision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(2, 1, 3, 4, false));

        assertTrue(decision.shouldComplete());
        assertTrue(decision.isSucceeded());
        assertEquals(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED, decision.completionStatus());
    }

    @Test
    void allSuccessful_shouldCompleteReturnsFailureToleranceExceededDecision() {
        var config = CompletionConfig.allSuccessful();

        var decision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(1, 1, 2, 3, false));

        assertTrue(decision.shouldComplete());
        assertFalse(decision.isSucceeded());
        assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, decision.completionStatus());
    }

    @Test
    void toleratedFailurePercentage_shouldCompleteUsesTotalCount() {
        var config = CompletionConfig.toleratedFailurePercentage(0.25);

        var continueDecision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(2, 1, 3, 4, false));
        var completeDecision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(2, 2, 4, 4, false));

        assertFalse(continueDecision.shouldComplete());
        assertTrue(completeDecision.shouldComplete());
        assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, completeDecision.completionStatus());
    }

    @Test
    void minSuccessful_tooFewRegisteredItems_shouldThrow() {
        var config = CompletionConfig.minSuccessful(3);

        var exception = assertThrows(IllegalStateException.class, () -> config.completionDecisionFunction()
                .apply(new CompletionConfig.CompletionStatus(2, 0, 2, 2, true)));

        assertEquals("minSuccessful (3) exceeds the number of registered items (2)", exception.getMessage());
    }

    @Test
    void shouldComplete_setsCustomCompletionDecisionFunction() {
        var config = CompletionConfig.shouldComplete(status -> status.successCount() >= 2
                ? CompletionConfig.CompletionDecision.complete(ConcurrencyCompletionStatus.CUSTOM_COMPLETION_SUCCEEDED)
                : CompletionConfig.CompletionDecision.continueExecution());

        assertNull(config.minSuccessful());
        assertNull(config.toleratedFailureCount());
        assertNull(config.toleratedFailurePercentage());
        assertNotNull(config.shouldComplete());
        assertTrue(config.hasCustomShouldComplete());

        var completeDecision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(2, 0, 2, 3, true));
        assertTrue(completeDecision.shouldComplete());
        assertTrue(completeDecision.isSucceeded());
        assertEquals(ConcurrencyCompletionStatus.CUSTOM_COMPLETION_SUCCEEDED, completeDecision.completionStatus());

        var continueDecision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(1, 0, 1, 3, true));
        assertFalse(continueDecision.shouldComplete());
    }

    @Test
    void shouldComplete_completionDecisionFunctionUsesCustomDecisionWhenAllItemsCompleted() {
        var config = CompletionConfig.shouldComplete(status -> CompletionConfig.CompletionDecision.continueExecution());

        var decision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(1, 1, 2, 2, true));

        assertFalse(decision.shouldComplete());
    }

    @Test
    void shouldComplete_canCompleteWithBuiltInFailureStatus() {
        var config = CompletionConfig.shouldComplete(status -> status.failureCount() > 0
                ? CompletionConfig.CompletionDecision.complete(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED)
                : CompletionConfig.CompletionDecision.continueExecution());

        var decision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(0, 1, 1, 3, true));

        assertTrue(config.hasCustomShouldComplete());
        assertTrue(decision.shouldComplete());
        assertFalse(decision.isSucceeded());
        assertEquals(ConcurrencyCompletionStatus.FAILURE_TOLERANCE_EXCEEDED, decision.completionStatus());
    }

    @Test
    void shouldComplete_canMarkCustomCompletionAsFailed() {
        var config = CompletionConfig.shouldComplete(status -> status.completedCount() >= 2
                ? CompletionConfig.CompletionDecision.complete(ConcurrencyCompletionStatus.CUSTOM_COMPLETION_FAILED)
                : CompletionConfig.CompletionDecision.continueExecution());

        var decision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(1, 1, 2, 3, true));

        assertTrue(decision.shouldComplete());
        assertFalse(decision.isSucceeded());
        assertEquals(ConcurrencyCompletionStatus.CUSTOM_COMPLETION_FAILED, decision.completionStatus());
    }

    @Test
    void shouldComplete_withNull_shouldThrow() {
        var exception = assertThrows(
                NullPointerException.class,
                () -> CompletionConfig.shouldComplete(
                        (Function<CompletionConfig.CompletionStatus, CompletionConfig.CompletionDecision>) null));
        assertEquals("shouldComplete cannot be null", exception.getMessage());
    }

    @Test
    void shouldComplete_withNullDecision_defersNullCheckToCaller() {
        var config = CompletionConfig.shouldComplete(status -> null);

        var decision =
                config.completionDecisionFunction().apply(new CompletionConfig.CompletionStatus(0, 0, 0, 1, false));

        assertNull(decision);
    }

    @Test
    void completionStatus_fourArgumentConstructorMarksCompletedItemsAsAllRegistered() {
        var status = new CompletionConfig.CompletionStatus(1, 1, 2, 2);

        assertTrue(status.allItemsRegistered());
        assertTrue(status.allCompleted());
    }

    @Test
    void completionDecision_withStatusWhenContinuing_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new CompletionConfig.CompletionDecision(false, ConcurrencyCompletionStatus.ALL_COMPLETED));
        assertEquals("completionStatus must be null when shouldComplete is false", exception.getMessage());
    }

    @Test
    void completionDecision_withoutStatusWhenCompleting_shouldThrow() {
        var exception =
                assertThrows(IllegalArgumentException.class, () -> new CompletionConfig.CompletionDecision(true, null));
        assertEquals("completionStatus is required when shouldComplete is true", exception.getMessage());
    }

    @Test
    void completionDecision_usesCompletionStatusSuccessFlag() {
        var decision =
                CompletionConfig.CompletionDecision.complete(ConcurrencyCompletionStatus.CUSTOM_COMPLETION_FAILED);

        assertTrue(decision.shouldComplete());
        assertFalse(decision.isSucceeded());
    }

    @Test
    void hasCustomShouldComplete_usesStoredField() {
        var config = CompletionConfig.shouldComplete(status ->
                CompletionConfig.CompletionDecision.complete(ConcurrencyCompletionStatus.CUSTOM_COMPLETION_SUCCEEDED));
        var equalConfig = CompletionConfig.allCompleted();

        assertNotEquals(config, equalConfig);
        assertNotNull(config.shouldComplete());
        assertTrue(config.hasCustomShouldComplete());
        assertFalse(equalConfig.hasCustomShouldComplete());
    }

    @Test
    void constructor_shouldCompleteWithThresholdFields_shouldThrow() {
        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> new CompletionConfig(
                        1,
                        null,
                        null,
                        status -> CompletionConfig.CompletionDecision.complete(
                                ConcurrencyCompletionStatus.CUSTOM_COMPLETION_SUCCEEDED)));

        assertEquals(
                "shouldComplete is mutually exclusive with minSuccessful, toleratedFailureCount, and toleratedFailurePercentage",
                exception.getMessage());
    }
}
