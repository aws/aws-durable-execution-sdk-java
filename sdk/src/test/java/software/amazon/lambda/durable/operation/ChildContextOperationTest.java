// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Unit tests for ChildContextOperation. */
class ChildContextOperationTest {

    private static final JacksonSerDes SERDES = new JacksonSerDes();

    private DurableContextImpl durableContext;
    private ExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        durableContext = mock(DurableContextImpl.class);
        executionManager = mock(ExecutionManager.class);
        when(durableContext.getExecutionManager()).thenReturn(executionManager);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("Root", ThreadType.CONTEXT));
        when(durableContext.getDurableConfig()).thenReturn(createConfig());
    }

    private DurableConfig createConfig() {
        return DurableConfig.builder()
                .withExecutorService(Executors.newCachedThreadPool())
                .build();
    }

    private static final OperationIdentifier OPERATION_IDENTIFIER =
            OperationIdentifier.of("1", "test-context", OperationType.CONTEXT, OperationSubType.RUN_IN_CHILD_CONTEXT);

    private ChildContextOperation<String> createOperation(Function<DurableContext, String> func) {
        return new ChildContextOperation<>(
                OPERATION_IDENTIFIER,
                func,
                TypeToken.get(String.class),
                RunInChildContextConfig.builder().serDes(SERDES).build(),
                durableContext);
    }

    private ChildContextOperation<String> createOperationWithParent(
            Function<DurableContext, String> func, ConcurrencyOperation<?> parent) {
        return new ChildContextOperation<>(
                OPERATION_IDENTIFIER,
                func,
                TypeToken.get(String.class),
                RunInChildContextConfig.builder().serDes(SERDES).build(),
                durableContext,
                parent);
    }

    // ===== SUCCEEDED replay =====

    /** SUCCEEDED replay returns cached result without re-executing the function. */
    @Test
    void replaySucceededReturnsCachedResult() {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(ContextDetails.builder()
                                .result("\"cached-value\"")
                                .build())
                        .build());

        var functionCalled = new AtomicBoolean(false);
        var operation = createOperation(ctx -> {
            functionCalled.set(true);
            return "should-not-execute";
        });

        operation.execute();
        var result = operation.get();

        assertEquals("cached-value", result);
        assertFalse(functionCalled.get(), "Function should not be called during SUCCEEDED replay");
    }

    // ===== FAILED replay =====

    /** FAILED replay throws the original exception without re-executing. */
    @Test
    void replayFailedThrowsOriginalException() {
        var originalException = new IllegalArgumentException("bad input");
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                        .status(OperationStatus.FAILED)
                        .contextDetails(ContextDetails.builder()
                                .error(ErrorObject.builder()
                                        .errorType("java.lang.IllegalArgumentException")
                                        .errorMessage("bad input")
                                        .errorData(SERDES.serialize(originalException))
                                        .stackTrace(stackTrace)
                                        .build())
                                .build())
                        .build());

        var functionCalled = new AtomicBoolean(false);
        var operation = createOperation(ctx -> {
            functionCalled.set(true);
            return "should-not-execute";
        });

        operation.execute();

        var thrown = assertThrows(IllegalArgumentException.class, operation::get);
        assertEquals("bad input", thrown.getMessage());
        assertFalse(functionCalled.get(), "Function should not be called during FAILED replay");
    }

    /** FAILED replay falls back to ChildContextFailedException when original cannot be reconstructed. */
    @Test
    void replayFailedFallsBackToChildContextFailedException() {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                        .status(OperationStatus.FAILED)
                        .contextDetails(ContextDetails.builder()
                                .error(ErrorObject.builder()
                                        .errorType("com.nonexistent.SomeException")
                                        .errorMessage("unknown error")
                                        .stackTrace(List.of("com.example.Test|method|Test.java|1"))
                                        .build())
                                .build())
                        .build());

        var operation = createOperation(ctx -> "unused");
        operation.execute();

        var thrown = assertThrows(ChildContextFailedException.class, operation::get);
        assertTrue(thrown.getMessage().contains("com.nonexistent.SomeException"));
        assertTrue(thrown.getMessage().contains("unknown error"));
    }

    // ===== Replay STARTED =====

    /** STARTED replay re-executes the child context (interrupted mid-execution). */
    @Test
    void replayStartedReExecutesChildContext() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                        .status(OperationStatus.STARTED)
                        .build());
        // hasOperationsForContext for the child context ID "1"
        when(executionManager.hasOperationsForContext("1")).thenReturn(false);

        var functionCalled = new AtomicBoolean(false);
        var operation = createOperation(ctx -> {
            functionCalled.set(true);
            return "re-executed";
        });

        operation.execute();

        // Give the executor thread time to run
        Thread.sleep(100);
        assertTrue(functionCalled.get(), "Function should be re-executed for STARTED replay");
    }

    // ===== ReplayChildren path =====

    /** SUCCEEDED with replayChildren=true re-executes to reconstruct the result. */
    @Test
    void replayChildrenReExecutesToReconstructResult() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().replayChildren(true).build())
                        .build());
        when(executionManager.hasOperationsForContext("1")).thenReturn(false);

        var functionCalled = new AtomicBoolean(false);
        var operation = createOperation(ctx -> {
            functionCalled.set(true);
            return "reconstructed-value";
        });

        operation.execute();

        // Give the executor thread time to run
        Thread.sleep(100);
        assertTrue(functionCalled.get(), "Function should be re-executed for replayChildren path");
    }

    // ===== Non-deterministic detection =====

    /** Type mismatch during replay terminates execution. */
    @Test
    void replayWithTypeMismatchTerminatesExecution() {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.STEP) // Wrong type — should be CONTEXT
                        .status(OperationStatus.SUCCEEDED)
                        .build());

        var operation = createOperation(ctx -> "unused");

        assertThrows(NonDeterministicExecutionException.class, operation::execute);
    }

    /** Name mismatch during replay terminates execution. */
    @Test
    void replayWithNameMismatchTerminatesExecution() {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("different-name") // Wrong name
                        .type(OperationType.CONTEXT)
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"value\"").build())
                        .build());

        var operation = createOperation(ctx -> "unused");

        assertThrows(NonDeterministicExecutionException.class, operation::execute);
    }

    // ===== Parent ConcurrencyOperation support =====

    /** Parent's onItemComplete() is called when child succeeds. */
    @Test
    void parentOnItemCompleteCalledOnChildSuccess() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);

        var parent = mock(ConcurrencyOperation.class);
        when(parent.isOperationCompleted()).thenReturn(false);

        var operation = createOperationWithParent(ctx -> "success", parent);
        operation.execute();
        Thread.sleep(200);

        verify(parent).onItemComplete(operation);
    }

    /** Parent's onItemComplete() is called when child fails. */
    @Test
    void parentOnItemCompleteCalledOnChildFailure() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);

        var parent = mock(ConcurrencyOperation.class);
        when(parent.isOperationCompleted()).thenReturn(false);

        var operation = createOperationWithParent(
                ctx -> {
                    throw new RuntimeException("branch failed");
                },
                parent);
        operation.execute();
        Thread.sleep(200);

        verify(parent).onItemComplete(operation);
    }

    /** Child skips success checkpoint when parent operation has already completed. */
    @Test
    void childSkipsSuccessCheckpointWhenParentAlreadyCompleted() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);

        var parent = mock(ConcurrencyOperation.class);
        when(parent.isOperationCompleted()).thenReturn(true);

        var operation = createOperationWithParent(ctx -> "result", parent);
        operation.execute();
        Thread.sleep(200);

        // sendOperationUpdate should only be called once for START, not for SUCCEED
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
    }

    /** Child skips failure checkpoint when parent operation has already completed. */
    @Test
    void childSkipsFailureCheckpointWhenParentAlreadyCompleted() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);

        var parent = mock(ConcurrencyOperation.class);
        when(parent.isOperationCompleted()).thenReturn(true);

        var operation = createOperationWithParent(
                ctx -> {
                    throw new RuntimeException("branch failed");
                },
                parent);
        operation.execute();
        Thread.sleep(200);

        // sendOperationUpdate should not be called with FAIL action
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.FAIL));
    }

    // ===== onItemComplete called during replay =====

    /** SUCCEEDED replay (terminal) — onItemComplete is called via markAlreadyCompleted(). */
    @Test
    void replaySucceeded_callsParentOnItemComplete() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"cached\"").build())
                        .build());

        var parent = mock(ConcurrencyOperation.class);
        when(parent.isOperationCompleted()).thenReturn(false);

        var operation = createOperationWithParent(ctx -> "unused", parent);
        operation.execute();

        verify(parent).onItemComplete(operation);
    }

    /** FAILED replay (terminal) — onItemComplete is called via markAlreadyCompleted(). */
    @Test
    void replayFailed_callsParentOnItemComplete() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                        .status(OperationStatus.FAILED)
                        .contextDetails(ContextDetails.builder()
                                .error(ErrorObject.builder()
                                        .errorType("java.lang.RuntimeException")
                                        .errorMessage("original failure")
                                        .build())
                                .build())
                        .build());

        var parent = mock(ConcurrencyOperation.class);
        when(parent.isOperationCompleted()).thenReturn(false);

        var operation = createOperationWithParent(ctx -> "unused", parent);
        operation.execute();

        verify(parent).onItemComplete(operation);
    }

    /** STARTED replay — child re-executes and onItemComplete is called from the finally block. */
    @Test
    void replayStarted_callsParentOnItemComplete() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                        .status(OperationStatus.STARTED)
                        .build());
        when(executionManager.hasOperationsForContext("1")).thenReturn(false);

        var parent = mock(ConcurrencyOperation.class);
        when(parent.isOperationCompleted()).thenReturn(false);

        var operation = createOperationWithParent(ctx -> "re-executed", parent);
        operation.execute();
        Thread.sleep(200);

        verify(parent).onItemComplete(operation);
    }

    /** replayChildren=true — child re-executes and onItemComplete is called from the finally block. */
    @Test
    void replayChildren_callsParentOnItemComplete() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType(OperationSubType.RUN_IN_CHILD_CONTEXT.getValue())
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().replayChildren(true).build())
                        .build());
        when(executionManager.hasOperationsForContext("1")).thenReturn(false);

        var parent = mock(ConcurrencyOperation.class);
        when(parent.isOperationCompleted()).thenReturn(false);

        var operation = createOperationWithParent(ctx -> "reconstructed", parent);
        operation.execute();
        Thread.sleep(200);

        verify(parent, atLeast(1)).onItemComplete(operation);
    }
}
