// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiRequestDelayedBatcherTest {
    private static final Duration SHORT_DELAY = Duration.ofMillis(1);
    private static final Duration LONG_DELAY = Duration.ofMillis(100);
    private static final int MAX_BATCH_SIZE = 3;
    private static final int MAX_BATCH_BINARY_SIZE_IN_BYTES = 200;

    private static class Input {}

    private Input input;
    private ApiRequestDelayedBatcher<Input> cut;
    private Consumer<List<Input>> doBatchAction;

    @BeforeEach
    void setUp() {
        input = mock(Input.class);
        doBatchAction = mock();
        cut = new ApiRequestDelayedBatcher<>(MAX_BATCH_SIZE, MAX_BATCH_BINARY_SIZE_IN_BYTES, item -> 0, doBatchAction);
    }

    @Test
    void whenSingleActionSubmitted_futureRemainsIncompleteUntilTimerFires() {
        var resultFuture = cut.submit(input, LONG_DELAY);
        assertThrows(TimeoutException.class, () -> resultFuture.get(50, TimeUnit.MILLISECONDS));

        verify(doBatchAction, never()).accept(any());
        assertFalse(resultFuture.isDone());
    }

    @Test
    void whenMultipleActionsSubmittedBelowMaxBatchSize_futuresRemainIncompleteUntilTimerFires() {
        var resultFutures = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < MAX_BATCH_SIZE - 1; i++) {
            resultFutures.add(cut.submit(input, LONG_DELAY));
        }

        assertThrows(TimeoutException.class, () -> resultFutures.get(0).get(50, TimeUnit.MILLISECONDS));
        verify(doBatchAction, never()).accept(any());
    }

    @Test
    void whenTimerFires_allPendingItemsAreFlushedInSingleBatch() {
        var resultFutures = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < MAX_BATCH_SIZE - 1; i++) {
            resultFutures.add(cut.submit(input, SHORT_DELAY));
        }

        CompletableFuture.allOf(resultFutures.toArray(CompletableFuture[]::new)).join();
        verify(doBatchAction).accept(any());
    }

    @Test
    void whenTimerFires_batchIsProcessedPromptly() {
        var startTime = System.nanoTime();

        var resultFuture = cut.submit(input, SHORT_DELAY);
        resultFuture.join();

        assertTrue(System.nanoTime() - startTime < Duration.ofMillis(50).toNanos());
        verify(doBatchAction).accept(any());
    }

    @Test
    void whenItemsExceedMaxBatchSize_flushingQueueSplitsIntoBatches() {
        var resultFutures = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < MAX_BATCH_SIZE * 2; i++) {
            resultFutures.add(cut.submit(input, SHORT_DELAY));
        }

        CompletableFuture.allOf(resultFutures.toArray(CompletableFuture[]::new)).join();
        verify(doBatchAction, times(2)).accept(any());
    }

    @Test
    void whenItemsExceedMaxBatchSize_eachBatchRespectsMaxItemCount() {
        var resultFutures = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < MAX_BATCH_SIZE * 2; i++) {
            resultFutures.add(cut.submit(input, SHORT_DELAY));
        }

        CompletableFuture.allOf(resultFutures.toArray(CompletableFuture[]::new)).join();
        verify(doBatchAction, times(2)).accept(argThat(list -> list.size() == MAX_BATCH_SIZE));
    }

    @Test
    void whenItemsExceedBinarySize_flushingQueueSplitsIntoBatches() {
        var largeCut = new ApiRequestDelayedBatcher<>(
                MAX_BATCH_SIZE, MAX_BATCH_BINARY_SIZE_IN_BYTES, item -> MAX_BATCH_BINARY_SIZE_IN_BYTES, doBatchAction);

        var future1 = largeCut.submit(input, SHORT_DELAY);
        var future2 = largeCut.submit(input, SHORT_DELAY);

        CompletableFuture.allOf(future1, future2).join();
        // Each item fills the entire byte budget, so each gets its own batch
        verify(doBatchAction, times(2)).accept(argThat(list -> list.size() == 1));
    }

    @Test
    void whenOversizedItemSubmitted_itStillGetsProcessedAlone() {
        // Item larger than maxBatchBytes — should still be processed as a single-item batch
        var oversizedCut = new ApiRequestDelayedBatcher<>(
                MAX_BATCH_SIZE,
                MAX_BATCH_BINARY_SIZE_IN_BYTES,
                item -> MAX_BATCH_BINARY_SIZE_IN_BYTES + 1,
                doBatchAction);

        var future = oversizedCut.submit(input, SHORT_DELAY);
        future.join();

        verify(doBatchAction).accept(argThat(list -> list.size() == 1));
    }

    @Test
    void whenBatchActionThrows_allFuturesCompleteWithThatException() {
        var batchCause = new RuntimeException();
        doThrow(batchCause).when(doBatchAction).accept(any());

        var resultFuture1 = cut.submit(input, SHORT_DELAY).handle((v, ex) -> {
            assertEquals(batchCause, ex);
            return null;
        });
        var resultFuture2 = cut.submit(input, SHORT_DELAY).handle((v, ex) -> {
            assertEquals(batchCause, ex);
            return null;
        });
        var resultFuture3 = cut.submit(input, SHORT_DELAY).handle((v, ex) -> {
            assertEquals(batchCause, ex);
            return null;
        });

        CompletableFuture.allOf(resultFuture1, resultFuture2, resultFuture3).join();
    }

    @Test
    void whenBatchActionSucceeds_allFuturesCompleteSuccessfully() {
        var input1 = mock(Input.class);
        var input2 = mock(Input.class);
        var input3 = mock(Input.class);

        var resultFuture1 = cut.submit(input1, SHORT_DELAY);
        var resultFuture2 = cut.submit(input2, SHORT_DELAY);
        var resultFuture3 = cut.submit(input3, SHORT_DELAY);

        CompletableFuture.allOf(resultFuture1, resultFuture2, resultFuture3).join();
    }

    @Test
    void whenBatchActionThrowsRuntimeException_futuresReceiveOriginalCause() {
        var rootCause = new RuntimeException("Root cause");
        doThrow(rootCause).when(doBatchAction).accept(any());

        var resultFuture1 = cut.submit(input, SHORT_DELAY).handle((v, ex) -> {
            assertEquals(rootCause, ex);
            return null;
        });
        var resultFuture2 = cut.submit(input, SHORT_DELAY).handle((v, ex) -> {
            assertEquals(rootCause, ex);
            return null;
        });
        var resultFuture3 = cut.submit(input, SHORT_DELAY).handle((v, ex) -> {
            assertEquals(rootCause, ex);
            return null;
        });

        CompletableFuture.allOf(resultFuture1, resultFuture2, resultFuture3).join();
    }

    @Test
    void whenShutdownCalled_pendingItemsAreFlushedImmediately() {
        var future = cut.submit(input, LONG_DELAY);
        assertFalse(future.isDone());

        cut.shutdown();

        assertTrue(future.isDone());
        verify(doBatchAction).accept(any());
    }

    @Test
    void whenEarlierDelaySubmitted_batchFlushesAtEarlierTime() {
        var future1 = cut.submit(input, LONG_DELAY);
        var future2 = cut.submit(input, SHORT_DELAY);

        CompletableFuture.allOf(future1, future2).join();
        verify(doBatchAction).accept(any());
    }

    @Test
    void whenNoItemsSubmitted_shutdownDoesNotInvokeBatchAction() {
        cut.shutdown();
        verify(doBatchAction, never()).accept(any());
    }

    @Test
    void whenMultipleBatchesFlushedViaShutdown_allFuturesComplete() {
        var future1 = cut.submit(input, LONG_DELAY);
        cut.shutdown();
        assertTrue(future1.isDone());

        var future2 = cut.submit(input, LONG_DELAY);
        cut.shutdown();
        assertTrue(future2.isDone());
    }
}
