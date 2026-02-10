// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiRequestBatcherTest {
    private static final Duration MAX_DELAY_MILLIS = Duration.ofMillis(100);
    private static final int MAX_BATCH_SIZE = 3;
    private static final int MAX_BATCH_BINARY_SIZE_IN_BYTES = 100;

    private static class Input {}

    private Input input;
    private Clock fixedClock;
    private ApiRequestBatcher<Input> cut;
    private Function<List<Input>, CompletableFuture<Void>> doBatchAction;
    private CompletableFuture<Void> batchResultFuture;

    @BeforeEach
    void setUp() {
        input = mock(Input.class);
        doBatchAction = mock();
        batchResultFuture = new CompletableFuture<>();
        fixedClock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
        cut = new ApiRequestBatcher<>(
                MAX_DELAY_MILLIS, MAX_BATCH_SIZE, MAX_BATCH_BINARY_SIZE_IN_BYTES, item -> 0, doBatchAction);

        when(doBatchAction.apply(any())).thenReturn(batchResultFuture);
    }

    @Test
    void whenSingleActionPerformed_anUncompletedFutureIsReturned() {
        CompletableFuture<Void> resultFuture = cut.doAction(input);

        verify(doBatchAction, never()).apply(any());
        assertFalse(resultFuture.isDone());
    }

    @Test
    void whenMultipleActionsPerformedBelowMaxBatchSize_anUncompletedFutureIsReturnedEachTime() {
        List<CompletableFuture<Void>> resultFutures = new ArrayList<>();
        for (int i = 0; i < MAX_BATCH_SIZE - 1; i++) {
            resultFutures.add(cut.doAction(input));
        }

        verify(doBatchAction, never()).apply(any());
        assertTrue(resultFutures.stream().noneMatch(CompletableFuture::isDone));
    }

    @Test
    void whenMultipleActionsPerformedMatchingMaxBatchSize_batchInvokeIsPerformed() {
        List<CompletableFuture<Void>> resultFutures = new ArrayList<>();
        for (int i = 0; i < MAX_BATCH_SIZE; i++) {
            resultFutures.add(cut.doAction(input));
        }

        verify(doBatchAction).apply(any());
        assertTrue(resultFutures.stream().noneMatch(CompletableFuture::isDone));
    }

    @Test
    void whenBatchInvokeThrows_allFuturesCompleteWithThatException() throws InterruptedException {
        CompletableFuture<Void> resultFuture1 = cut.doAction(input);
        CompletableFuture<Void> resultFuture2 = cut.doAction(input);
        CompletableFuture<Void> resultFuture3 = cut.doAction(input);

        assertFalse(resultFuture1.isDone());
        assertFalse(resultFuture2.isDone());
        assertFalse(resultFuture3.isDone());

        Throwable batchCause = mock(Throwable.class);
        batchResultFuture.completeExceptionally(batchCause);

        assertTrue(resultFuture1.isCompletedExceptionally());
        assertTrue(resultFuture2.isCompletedExceptionally());
        assertTrue(resultFuture3.isCompletedExceptionally());

        assertEquals(batchCause, getFutureCause(resultFuture1));
        assertEquals(batchCause, getFutureCause(resultFuture2));
        assertEquals(batchCause, getFutureCause(resultFuture3));
    }

    @Test
    void whenBatchInvokeReturnsOutcome_allFuturesCompleteSuccessfully() {
        Input input1 = mock(Input.class);
        Input input2 = mock(Input.class);
        Input input3 = mock(Input.class);

        CompletableFuture<Void> resultFuture1 = cut.doAction(input1);
        CompletableFuture<Void> resultFuture2 = cut.doAction(input2);
        CompletableFuture<Void> resultFuture3 = cut.doAction(input3);

        assertFalse(resultFuture1.isDone());
        assertFalse(resultFuture2.isDone());
        assertFalse(resultFuture3.isDone());

        batchResultFuture.complete(null);

        assertTrue(resultFuture1.isDone());
        assertTrue(resultFuture2.isDone());
        assertTrue(resultFuture3.isDone());

        assertFalse(resultFuture1.isCompletedExceptionally());
        assertFalse(resultFuture2.isCompletedExceptionally());
        assertFalse(resultFuture3.isCompletedExceptionally());
    }

    @Test
    void testDoAction_whenCannotAddItemDueToBinarySizeConstraint_thenFlushCurrentBatchAndCreateNewOne() {
        var cut = new ApiRequestBatcher<>(
                MAX_DELAY_MILLIS,
                MAX_BATCH_SIZE,
                MAX_BATCH_BINARY_SIZE_IN_BYTES,
                item -> MAX_BATCH_BINARY_SIZE_IN_BYTES,
                doBatchAction);
        List<CompletableFuture<Void>> resultFutures = new ArrayList<>();

        resultFutures.add(cut.doAction(input));
        resultFutures.add(cut.doAction(input));

        verify(doBatchAction).apply(any());

        assertTrue(resultFutures.stream().noneMatch(CompletableFuture::isDone));
    }

    @Test
    void whenTimerFires_batchIsProcessed() {
        var timerCut = new ApiRequestBatcher<>(
                Duration.ofMillis(1), MAX_BATCH_SIZE, MAX_BATCH_BINARY_SIZE_IN_BYTES, item -> 0, doBatchAction);

        CompletableFuture<Void> resultFuture = timerCut.doAction(input);

        // Wait for the timeout to trigger
        CompletableFuture.delayedExecutor(10, TimeUnit.MILLISECONDS).execute(() -> {});

        verify(doBatchAction, timeout(50)).apply(any());
        assertFalse(resultFuture.isDone());
    }

    @Test
    void whenBatchInvokeThrowsCompletionException_allFuturesCompleteWithUnwrappedCause() throws InterruptedException {
        CompletableFuture<Void> resultFuture1 = cut.doAction(input);
        CompletableFuture<Void> resultFuture2 = cut.doAction(input);
        CompletableFuture<Void> resultFuture3 = cut.doAction(input);

        RuntimeException rootCause = new RuntimeException("Root cause");
        batchResultFuture.completeExceptionally(rootCause);

        assertTrue(resultFuture1.isCompletedExceptionally());
        assertTrue(resultFuture2.isCompletedExceptionally());
        assertTrue(resultFuture3.isCompletedExceptionally());

        // Should get unwrapped root cause, not the CompletionException wrapper
        assertEquals(rootCause, getFutureCause(resultFuture1));
        assertEquals(rootCause, getFutureCause(resultFuture2));
        assertEquals(rootCause, getFutureCause(resultFuture3));
    }

    private Throwable getFutureCause(CompletableFuture<?> failedFuture) throws InterruptedException {
        try {
            failedFuture.get();
            return null;
        } catch (ExecutionException cause) {
            return cause.getCause();
        }
    }
}
