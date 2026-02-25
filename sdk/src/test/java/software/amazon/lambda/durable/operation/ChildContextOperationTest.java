// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Unit tests for ChildContextOperation. */
class ChildContextOperationTest {

    private static final JacksonSerDes SERDES = new JacksonSerDes();

    private ExecutionManager createMockExecutionManager() {
        var executionManager = mock(ExecutionManager.class);
        when(executionManager.getCurrentThreadContext()).thenReturn(new ThreadContext("Root", ThreadType.CONTEXT));
        return executionManager;
    }

    private DurableConfig createConfig() {
        return DurableConfig.builder()
                .withExecutorService(Executors.newCachedThreadPool())
                .build();
    }

    private ChildContextOperation<String> createOperation(
            ExecutionManager executionManager,
            java.util.function.Function<software.amazon.lambda.durable.DurableContext, String> func) {
        return new ChildContextOperation<>(
                "1",
                "test-context",
                func,
                TypeToken.get(String.class),
                SERDES,
                executionManager,
                createConfig(),
                null,
                null);
    }

    // ===== SUCCEEDED replay =====

    /** SUCCEEDED replay returns cached result without re-executing the function. */
    @Test
    void replaySucceededReturnsCachedResult() {
        var executionManager = createMockExecutionManager();
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(ContextDetails.builder()
                                .result("\"cached-value\"")
                                .build())
                        .build());

        var functionCalled = new AtomicBoolean(false);
        var operation = createOperation(executionManager, ctx -> {
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
        var executionManager = createMockExecutionManager();
        var originalException = new IllegalArgumentException("bad input");
        var stackTrace = List.of("com.example.Test|method|Test.java|42");

        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
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
        var operation = createOperation(executionManager, ctx -> {
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
        var executionManager = createMockExecutionManager();

        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .status(OperationStatus.FAILED)
                        .contextDetails(ContextDetails.builder()
                                .error(ErrorObject.builder()
                                        .errorType("com.nonexistent.SomeException")
                                        .errorMessage("unknown error")
                                        .stackTrace(List.of("com.example.Test|method|Test.java|1"))
                                        .build())
                                .build())
                        .build());

        var operation = createOperation(executionManager, ctx -> "unused");
        operation.execute();

        var thrown = assertThrows(ChildContextFailedException.class, operation::get);
        assertTrue(thrown.getMessage().contains("com.nonexistent.SomeException"));
        assertTrue(thrown.getMessage().contains("unknown error"));
    }

    // ===== Replay STARTED =====

    /** STARTED replay re-executes the child context (interrupted mid-execution). */
    @Test
    void replayStartedReExecutesChildContext() throws Exception {
        var executionManager = createMockExecutionManager();
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .status(OperationStatus.STARTED)
                        .build());
        // hasOperationsForContext for the child context ID "1"
        when(executionManager.hasOperationsForContext("1")).thenReturn(false);

        var functionCalled = new AtomicBoolean(false);
        var operation = createOperation(executionManager, ctx -> {
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
        var executionManager = createMockExecutionManager();
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().replayChildren(true).build())
                        .build());
        when(executionManager.hasOperationsForContext("1")).thenReturn(false);

        var functionCalled = new AtomicBoolean(false);
        var operation = createOperation(executionManager, ctx -> {
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
        var executionManager = createMockExecutionManager();
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.STEP) // Wrong type — should be CONTEXT
                        .status(OperationStatus.SUCCEEDED)
                        .build());

        var operation = createOperation(executionManager, ctx -> "unused");

        assertThrows(NonDeterministicExecutionException.class, operation::execute);
    }

    /** Name mismatch during replay terminates execution. */
    @Test
    void replayWithNameMismatchTerminatesExecution() {
        var executionManager = createMockExecutionManager();
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("different-name") // Wrong name
                        .type(OperationType.CONTEXT)
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(
                                ContextDetails.builder().result("\"value\"").build())
                        .build());

        var operation = createOperation(executionManager, ctx -> "unused");

        assertThrows(NonDeterministicExecutionException.class, operation::execute);
    }
}
