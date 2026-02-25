// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiRequestBatcherTest {
    private static final Duration MAX_DELAY_MILLIS = Duration.ofMillis(100);
    private static final int MAX_BATCH_SIZE = 3;
    private static final int MAX_BATCH_BINARY_SIZE_IN_BYTES = 100;

    private static class Input {}

    private Input input;
    private ApiRequestBatcher<Input> cut;
    private Consumer<List<Input>> doBatchAction;

    @BeforeEach
    void setUp() {
        input = mock(Input.class);
        doBatchAction = mock();
        cut = new ApiRequestBatcher<>(MAX_BATCH_SIZE, MAX_BATCH_BINARY_SIZE_IN_BYTES, item -> 0, doBatchAction);
    }

    @Test
    void whenSingleActionPerformed_anUncompletedFutureIsReturned() {
        CompletableFuture<Void> resultFuture = cut.submit(input, MAX_DELAY_MILLIS);
        assertThrows(TimeoutException.class, () -> resultFuture.get(50, TimeUnit.MILLISECONDS));

        verify(doBatchAction, never()).accept(any());
        assertFalse(resultFuture.isDone());
    }

    @Test
    void whenMultipleActionsPerformedBelowMaxBatchSize_anUncompletedFutureIsReturnedEachTime()
            throws ExecutionException, InterruptedException, TimeoutException {
        List<CompletableFuture<Void>> resultFutures = new ArrayList<>();
        for (int i = 0; i < MAX_BATCH_SIZE - 1; i++) {
            resultFutures.add(cut.submit(input, MAX_DELAY_MILLIS));
        }

        assertThrows(TimeoutException.class, () -> resultFutures.get(0).get(50, TimeUnit.MILLISECONDS));

        verify(doBatchAction, never()).accept(any());
    }

    @Test
    void whenMultipleActionsPerformedMatchingMaxBatchSize_batchInvokeIsPerformed() {
        List<CompletableFuture<Void>> resultFutures = new ArrayList<>();
        for (int i = 0; i < MAX_BATCH_SIZE * 2; i++) {
            resultFutures.add(cut.submit(input, MAX_DELAY_MILLIS));
        }

        CompletableFuture.allOf(resultFutures.toArray(CompletableFuture[]::new)).join();
        verify(doBatchAction, times(2)).accept(any());
    }

    @Test
    void whenBatchInvokeThrows_allFuturesCompleteWithThatException() {
        Throwable batchCause = new RuntimeException();
        doThrow(batchCause).when(doBatchAction).accept(any());

        CompletableFuture<Void> resultFuture1 = cut.submit(input, MAX_DELAY_MILLIS)
                .handle((v, ex) -> {
                    assertEquals(batchCause, ex);
                    return null;
                });
        CompletableFuture<Void> resultFuture2 = cut.submit(input, MAX_DELAY_MILLIS)
                .handle((v, ex) -> {
                    assertEquals(batchCause, ex);
                    return null;
                });
        CompletableFuture<Void> resultFuture3 = cut.submit(input, MAX_DELAY_MILLIS)
                .handle((v, ex) -> {
                    assertEquals(batchCause, ex);
                    return null;
                });

        CompletableFuture.allOf(resultFuture1, resultFuture2, resultFuture3).join();
    }

    @Test
    void whenBatchInvokeReturns_allFuturesCompleteSuccessfully() {
        Input input1 = mock(Input.class);
        Input input2 = mock(Input.class);
        Input input3 = mock(Input.class);

        CompletableFuture<Void> resultFuture1 = cut.submit(input1, MAX_DELAY_MILLIS);
        CompletableFuture<Void> resultFuture2 = cut.submit(input2, MAX_DELAY_MILLIS);
        CompletableFuture<Void> resultFuture3 = cut.submit(input3, MAX_DELAY_MILLIS);

        CompletableFuture.allOf(resultFuture1, resultFuture2, resultFuture3).join();
    }

    @Test
    void testSubmit_whenCannotAddItemDueToBinarySizeConstraint_thenFlushCurrentBatchAndCreateNewOne() {
        var cut = new ApiRequestBatcher<>(
                MAX_BATCH_SIZE, MAX_BATCH_BINARY_SIZE_IN_BYTES, item -> MAX_BATCH_BINARY_SIZE_IN_BYTES, doBatchAction);

        var future1 = cut.submit(input, MAX_DELAY_MILLIS);
        var future2 = cut.submit(input, MAX_DELAY_MILLIS);
        CompletableFuture.allOf(future1, future2)
                .thenAccept(v -> verify(doBatchAction, times(2)).accept(any()))
                .join();
    }

    @Test
    void whenTimerFires_batchIsProcessed() {
        var timerCut =
                new ApiRequestBatcher<>(MAX_BATCH_SIZE, MAX_BATCH_BINARY_SIZE_IN_BYTES, item -> 0, doBatchAction);
        long startTime = System.nanoTime();

        CompletableFuture<Void> resultFuture = timerCut.submit(input, Duration.ofMillis(1));

        // Wait for the timeout to trigger
        CompletableFuture.allOf(resultFuture).join();

        assertTrue(System.nanoTime() - startTime < Duration.ofMillis(20).toNanos());
        verify(doBatchAction).accept(any());
    }

    @Test
    void whenBatchInvokeThrowsCompletionException_allFuturesCompleteWithUnwrappedCause() throws InterruptedException {
        RuntimeException rootCause = new RuntimeException("Root cause");
        doThrow(rootCause).when(doBatchAction).accept(any());

        CompletableFuture<Void> resultFuture1 = cut.submit(input, MAX_DELAY_MILLIS)
                .handle((v, ex) -> {
                    assertEquals(rootCause, ex);
                    return null;
                });
        CompletableFuture<Void> resultFuture2 = cut.submit(input, MAX_DELAY_MILLIS)
                .handle((v, ex) -> {
                    assertEquals(rootCause, ex);
                    return null;
                });
        CompletableFuture<Void> resultFuture3 = cut.submit(input, MAX_DELAY_MILLIS)
                .handle((v, ex) -> {
                    assertEquals(rootCause, ex);
                    return null;
                });
        CompletableFuture.allOf(resultFuture1, resultFuture2, resultFuture3).join();
    }
}
