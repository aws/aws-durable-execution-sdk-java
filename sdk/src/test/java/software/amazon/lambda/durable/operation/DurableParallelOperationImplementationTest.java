// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static software.amazon.lambda.durable.model.OperationSubType.PARALLEL;
import static software.amazon.lambda.durable.model.OperationSubType.PARALLEL_BRANCH;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.exception.ParallelBranchFailedException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFailure;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.operation.DurableParallelOperation.ParallelConfig;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class DurableParallelOperationImplementationTest {
    @Test
    void executeBuildsParallelAndBranchContextsFromReservations() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var parentFuture = mockParallelResultFuture();
        var serDes = new JacksonSerDes();
        when(context.getDurableConfig())
                .thenReturn(DurableConfig.builder().withSerDes(serDes).build());
        when(context.reserve("parallel")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(PARALLEL.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(parentFuture);

        var parallel = DurableParallelOperation.parallel(
                context,
                "parallel",
                ParallelConfig.builder()
                        .nestingType(DurableConcurrencyOperation.NestingType.FLAT)
                        .build());
        var firstFuture = parallel.branch("first", String.class, child -> "first");
        var secondFuture = parallel.branch("second", String.class, child -> "second");
        assertTrue(parallel.completionFuture().isDone());
        parallel.close();

        var parentFunction = extensionFunction();
        var parentConfig = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(parent)
                .runInChildContextAsync(
                        eq(PARALLEL.getValue()),
                        any(TypeToken.class),
                        parentFunction.capture(),
                        parentConfig.capture());
        assertFalse(parentConfig.getValue().emitUserFunctionEvents());
        assertTrue(parentConfig.getValue().suppressLateChildCheckpoints());
        assertSame(serDes, parentConfig.getValue().serDes());

        var child = mock(CurrentContext.class);
        var first = mock(ExtensionOperation.class);
        var second = mock(ExtensionOperation.class);
        when(child.reserve("first")).thenReturn(first);
        when(child.reserve("second")).thenReturn(second);
        when(first.runInChildContextAsync(
                        eq(PARALLEL_BRANCH.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(CompletableFuture.completedFuture("first"));
        when(second.runInChildContextAsync(
                        eq(PARALLEL_BRANCH.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(CompletableFuture.completedFuture("second"));

        ParallelResult result;
        try (var ignoredContext = BaseContextImpl.attachCurrentContext(child);
                var ignoredReplay = ExtensionContextReplayContext.attach(false, null)) {
            result = parentFunction
                    .getValue()
                    .apply()
                    .toCompletableFuture()
                    .join()
                    .result();
        }

        assertEquals(2, result.size());
        assertEquals(2, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(0, result.skipped());
        assertEquals(ConcurrencyCompletionStatus.ALL_COMPLETED, result.completionStatus());
        assertEquals(List.of(ParallelResult.Status.SUCCEEDED, ParallelResult.Status.SUCCEEDED), result.statuses());
        assertEquals("first", firstFuture.get());
        assertEquals("second", secondFuture.get());

        var branchConfig = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(first)
                .runInChildContextAsync(
                        eq(PARALLEL_BRANCH.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        branchConfig.capture());
        assertTrue(branchConfig.getValue().isVirtual());
        assertSame(serDes, branchConfig.getValue().serDes());
        var failedBranch = Operation.builder()
                .id("branch-id")
                .name("first")
                .type(OperationType.CONTEXT)
                .subType(PARALLEL_BRANCH.getValue())
                .status(OperationStatus.FAILED)
                .build();
        var translated = branchConfig
                .getValue()
                .errorHandler()
                .translate(new ExtensionContextFailure(failedBranch, null, List.of()));
        var failure = assertInstanceOf(ParallelBranchFailedException.class, translated);
        assertSame(failedBranch, failure.getOperation());
    }

    @Test
    void replaySkipsBranchesMissingFromCompletedResult() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        when(context.getDurableConfig()).thenReturn(DurableConfig.builder().build());
        when(context.reserve("parallel")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(PARALLEL.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(mockParallelResultFuture());

        var parallel = DurableParallelOperation.parallel(
                context, "parallel", ParallelConfig.builder().build());
        parallel.branch("skipped", String.class, child -> "skipped");
        parallel.branch("completed", String.class, child -> "completed");
        parallel.close();

        var parentFunction = extensionFunction();
        verify(parent)
                .runInChildContextAsync(eq(PARALLEL.getValue()), any(TypeToken.class), parentFunction.capture(), any());
        var child = mock(CurrentContext.class);
        var skipped = mock(ExtensionOperation.class);
        var completed = mock(ExtensionOperation.class);
        when(child.reserve("skipped")).thenReturn(skipped);
        when(child.reserve("completed")).thenReturn(completed);
        when(completed.runInChildContextAsync(
                        eq(PARALLEL_BRANCH.getValue()),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(CompletableFuture.completedFuture("completed"));
        var replayState = new ParallelResult(
                2,
                1,
                0,
                1,
                ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED,
                List.of(ParallelResult.Status.SKIPPED, ParallelResult.Status.SUCCEEDED));

        ParallelResult result;
        try (var ignoredContext = BaseContextImpl.attachCurrentContext(child);
                var ignoredReplay = ExtensionContextReplayContext.attach(true, replayState)) {
            result = parentFunction
                    .getValue()
                    .apply()
                    .toCompletableFuture()
                    .join()
                    .result();
        }

        assertEquals(replayState, result);
        verify(skipped, never())
                .runInChildContextAsync(
                        any(String.class),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class));
    }

    @Test
    void getIncludesLateBranchesAsSkipped() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var parentFuture = new CompletableFuture<ParallelResult>();
        when(context.getDurableConfig()).thenReturn(DurableConfig.builder().build());
        when(context.reserve("parallel")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(PARALLEL.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(parentFuture);
        parentFuture.complete(new ParallelResult(
                1,
                1,
                0,
                0,
                ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED,
                List.of(ParallelResult.Status.SUCCEEDED)));

        var parallel = DurableParallelOperation.parallel(
                context, "parallel", ParallelConfig.builder().build());
        parallel.branch("completed", String.class, child -> "completed");
        parallel.branch("late", String.class, child -> "late");

        var result = parallel.get();

        assertEquals(2, result.size());
        assertEquals(1, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(1, result.skipped());
        assertEquals(List.of(ParallelResult.Status.SUCCEEDED, ParallelResult.Status.SKIPPED), result.statuses());
    }

    @Test
    void branchAfterCloseFails() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        when(context.getDurableConfig()).thenReturn(DurableConfig.builder().build());
        when(context.reserve("parallel")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq(PARALLEL.getValue()),
                        any(TypeToken.class),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(mockParallelResultFuture());
        var parallel = DurableParallelOperation.parallel(
                context, "parallel", ParallelConfig.builder().build());

        parallel.close();

        var exception =
                assertThrows(IllegalStateException.class, () -> parallel.branch("late", String.class, child -> "late"));
        assertEquals("Cannot add branches after join() has been called", exception.getMessage());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<ExtensionContextFunction<ParallelResult>> extensionFunction() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ExtensionContextFunction.class);
    }

    private CompletableFuture<ParallelResult> mockParallelResultFuture() {
        return CompletableFuture.completedFuture(
                new ParallelResult(0, 0, 0, 0, ConcurrencyCompletionStatus.ALL_COMPLETED, List.of()));
    }

    private interface CurrentContext extends DurableContext, ExtensionContext {}
}
