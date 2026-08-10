// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.primitive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFailure;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/** Unit tests for ChildContextPrimitive. */
class ChildContextPrimitiveTest {

    private static final class SerializationOnlySerDes implements SerDes {
        @Override
        public String serialize(Object value) {
            return "\"serialized\"";
        }

        @Override
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            throw new SerDesException("cannot deserialize");
        }
    }

    private static final class NormalizingSerDes implements SerDes {
        @Override
        public String serialize(Object value) {
            return "\"serialized\"";
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            return (T) "deserialized";
        }
    }

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
        return createConfig(true);
    }

    private DurableConfig createConfig(boolean deserializeAfterSerialization) {
        return DurableConfig.builder()
                .withExecutorService(Executors.newCachedThreadPool())
                .withDeserializeAfterSerialization(deserializeAfterSerialization)
                .build();
    }

    private static final OperationIdentifier OPERATION_IDENTIFIER =
            OperationIdentifier.of("1", "test-context", OperationSubType.RUN_IN_CHILD_CONTEXT);

    private ChildContextPrimitive<String> createOperation(Function<DurableContext, String> func) {
        return createOperation(func, SERDES);
    }

    private ChildContextPrimitive<String> createOperation(Function<DurableContext, String> func, SerDes serDes) {
        return new ChildContextPrimitive<>(
                OPERATION_IDENTIFIER,
                func,
                TypeToken.get(String.class),
                RunInChildContextConfig.builder().serDes(serDes).build(),
                durableContext);
    }

    private ChildContextPrimitive<String> createVirtualOperation(Function<DurableContext, String> func) {
        return createVirtualOperation(func, SERDES);
    }

    private ChildContextPrimitive<String> createVirtualOperation(Function<DurableContext, String> func, SerDes serDes) {
        return new ChildContextPrimitive<>(
                OPERATION_IDENTIFIER,
                func,
                TypeToken.get(String.class),
                RunInChildContextConfig.builder().serDes(serDes).isVirtual(true).build(),
                durableContext);
    }

    private ChildContextPrimitive<String> createOperationWithParent(
            Function<DurableContext, String> func, BasePrimitive parent) {
        return new ChildContextPrimitive<>(
                OPERATION_IDENTIFIER,
                func,
                TypeToken.get(String.class),
                RunInChildContextConfig.builder().serDes(SERDES).build(),
                durableContext,
                parent);
    }

    private ChildContextPrimitive<String> createExtensionOperation(ExtensionContextConfig config) {
        return createExtensionOperation("AcmeContext", config);
    }

    private ChildContextPrimitive<String> createExtensionOperation(String subType, ExtensionContextConfig config) {
        return createExtensionOperation(subType, () -> ExtensionContextResult.completed("unused"), config);
    }

    private ChildContextPrimitive<String> createExtensionOperation(
            String subType, ExtensionContextFunction<String> function, ExtensionContextConfig config) {
        return new ChildContextPrimitive<>(
                new OperationIdentifier("1", "test-context", OperationType.CONTEXT, subType),
                function,
                TypeToken.get(String.class),
                config,
                durableContext);
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

    /** Virtual contexts are always executed, even during SUCCEEDED replay. */
    @Test
    void executeVirtualContext() {
        var functionCalled = new AtomicBoolean(false);
        var operation = createVirtualOperation(ctx -> {
            functionCalled.set(true);
            return "should-execute";
        });

        operation.execute();
        var result = operation.get();

        assertEquals("should-execute", result);
        assertTrue(functionCalled.get(), "Function should be called during SUCCEEDED replay");
    }

    @Test
    void virtualChildReturnsDeserializedResult() {
        var operation = createVirtualOperation(ctx -> "raw", new NormalizingSerDes());

        operation.execute();

        assertEquals("deserialized", operation.get());
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

    @Test
    void replayKnownSubtypeWithoutErrorHandlerUsesGenericFailure() {
        var failedContext = Operation.builder()
                .id("1")
                .name("test-context")
                .type(OperationType.CONTEXT)
                .subType(OperationSubType.MAP_ITERATION.getValue())
                .status(OperationStatus.FAILED)
                .contextDetails(ContextDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("com.nonexistent.SomeException")
                                .errorMessage("unknown error")
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(failedContext);
        var config = ExtensionContextConfig.builder().serDes(SERDES).build();
        var operation = createExtensionOperation(OperationSubType.MAP_ITERATION.getValue(), config);

        operation.execute();

        var thrown = assertThrows(ChildContextFailedException.class, operation::get);
        assertSame(failedContext, thrown.getOperation());
    }

    @Test
    void replayFailedUsesExtensionErrorHandlerWithChildSummaries() {
        var contextError = ErrorObject.builder()
                .errorType("com.nonexistent.ContextException")
                .errorMessage("context failed")
                .build();
        var childError = ErrorObject.builder()
                .errorType("com.nonexistent.ChildException")
                .errorMessage("child failed")
                .build();
        var failedContext = Operation.builder()
                .id("1")
                .name("test-context")
                .type(OperationType.CONTEXT)
                .subType("AcmeContext")
                .status(OperationStatus.FAILED)
                .contextDetails(ContextDetails.builder().error(contextError).build())
                .build();
        var failedChild = Operation.builder()
                .id("1-1")
                .name("child")
                .type(OperationType.STEP)
                .subType("AcmeChild")
                .status(OperationStatus.FAILED)
                .stepDetails(StepDetails.builder().error(childError).build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(failedContext);
        when(executionManager.getChildOperations("1")).thenReturn(List.of(failedChild));
        var capturedFailure = new AtomicReference<ExtensionContextFailure>();
        var translated = new IllegalStateException("translated");
        var config = ExtensionContextConfig.builder()
                .serDes(SERDES)
                .errorHandler(failure -> {
                    capturedFailure.set(failure);
                    return translated;
                })
                .build();

        var operation = createExtensionOperation(config);
        operation.execute();

        assertSame(translated, assertThrows(IllegalStateException.class, operation::get));
        var failure = capturedFailure.get();
        assertSame(failedContext, failure.operation());
        assertEquals("test-context", failure.contextName());
        assertEquals("AcmeContext", failure.subType());
        assertEquals(contextError, failure.error());
        assertEquals(1, failure.childOperations().size());
        assertEquals(OperationType.STEP, failure.childOperations().get(0).operationType());
        assertEquals("AcmeChild", failure.childOperations().get(0).subType());
        assertEquals(childError, failure.childOperations().get(0).error());
    }

    @Test
    void replayFailedPrefersReconstructedExceptionOverExtensionHandler() {
        var originalException = new IllegalArgumentException("bad input");
        var failedContext = Operation.builder()
                .id("1")
                .name("test-context")
                .type(OperationType.CONTEXT)
                .subType("AcmeContext")
                .status(OperationStatus.FAILED)
                .contextDetails(ContextDetails.builder()
                        .error(ErrorObject.builder()
                                .errorType("java.lang.IllegalArgumentException")
                                .errorMessage("bad input")
                                .errorData(SERDES.serialize(originalException))
                                .stackTrace(List.of("com.example.Test|method|Test.java|42"))
                                .build())
                        .build())
                .build();
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(failedContext);
        var handlerCalled = new AtomicBoolean();
        var config = ExtensionContextConfig.builder()
                .serDes(SERDES)
                .errorHandler(failure -> {
                    handlerCalled.set(true);
                    return new IllegalStateException("translated");
                })
                .build();

        var operation = createExtensionOperation(config);
        operation.execute();

        var thrown = assertThrows(IllegalArgumentException.class, operation::get);
        assertEquals("bad input", thrown.getMessage());
        assertFalse(handlerCalled.get());
    }

    @Test
    void suppressingExtensionContextPropagatesCompletionOwnerToChildContext() {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);
        when(executionManager.sendOperationUpdate(any())).thenReturn(CompletableFuture.completedFuture(null));
        var childContext = mock(DurableContextImpl.class);
        when(childContext.getDurableConfig()).thenReturn(createConfig());
        var config = ExtensionContextConfig.builder()
                .serDes(SERDES)
                .suppressLateChildCheckpoints(true)
                .build();
        var operation = createExtensionOperation(config);
        when(durableContext.createChildContext("1", "test-context", false, operation))
                .thenReturn(childContext);

        operation.execute();

        verify(durableContext, timeout(1000)).createChildContext("1", "test-context", false, operation);
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

    @Test
    void extensionReplayChildrenAcceptsLegacyEmptyResultPayload() {
        when(executionManager.getOperationAndUpdateReplayState("1"))
                .thenReturn(Operation.builder()
                        .id("1")
                        .name("test-context")
                        .type(OperationType.CONTEXT)
                        .subType("AcmeContext")
                        .status(OperationStatus.SUCCEEDED)
                        .contextDetails(ContextDetails.builder()
                                .result("")
                                .replayChildren(true)
                                .build())
                        .build());
        when(executionManager.hasOperationsForContext("1")).thenReturn(false);
        var replayState = new AtomicReference<String>();
        var config = ExtensionContextConfig.builder().serDes(SERDES).build();
        var operation = createExtensionOperation(
                "AcmeContext",
                () -> {
                    var replayContext = ExtensionContextReplayContext.<String>getCurrentContext();
                    assertTrue(replayContext.isReplayingChildren());
                    replayState.set(replayContext.getReplayState());
                    return ExtensionContextResult.completed("reconstructed");
                },
                config);

        operation.execute();

        assertEquals("reconstructed", operation.get());
        assertNull(replayState.get());
    }

    @Test
    void extensionReplayChildrenWithoutStateCheckpointsLegacyEmptyPayload() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);
        var successUpdate = new AtomicReference<OperationUpdate>();
        var successSent = new CountDownLatch(1);
        when(executionManager.sendOperationUpdate(any())).thenAnswer(invocation -> {
            var update = invocation.<OperationUpdate>getArgument(0);
            if (update.action() == OperationAction.SUCCEED) {
                successUpdate.set(update);
                successSent.countDown();
            }
            return CompletableFuture.completedFuture(null);
        });
        var config = ExtensionContextConfig.builder().serDes(SERDES).build();
        var operation = createExtensionOperation(
                "AcmeContext", () -> ExtensionContextResult.replayChildrenAboveSize("large", null, 1), config);

        operation.execute();

        assertTrue(successSent.await(2, TimeUnit.SECONDS));
        assertEquals("", successUpdate.get().payload());
        assertTrue(successUpdate.get().contextOptions().replayChildren());
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

    // ===== Parent operation support =====

    /** Child skips success checkpoint when parent operation has already completed. */
    @Test
    void childSkipsSuccessCheckpointWhenParentAlreadyCompleted() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);

        var parent = new CompletedParentOperation(durableContext);

        var operation = createOperationWithParent(ctx -> "result", parent);
        operation.execute();
        Thread.sleep(200);

        // sendOperationUpdate should only be called once for START, not for SUCCEED
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
    }

    /** Virtual child still validates result round-trip before skipping a success checkpoint. */
    @Test
    void virtualChildFailsWhenResultCannotBeDeserialized() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);

        var operation = createVirtualOperation(ctx -> "result", new SerializationOnlySerDes());
        operation.execute();
        Thread.sleep(200);

        var thrown = assertThrows(ChildContextFailedException.class, operation::get);
        assertTrue(thrown.getMessage().contains(SerDesException.class.getName()));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.FAIL));
    }

    /** Virtual child can skip result deserialization when disabled in DurableConfig. */
    @Test
    void virtualChildSucceedsWhenResultValidationDisabled() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);
        when(durableContext.getDurableConfig()).thenReturn(createConfig(false));

        var operation = createVirtualOperation(ctx -> "result", new SerializationOnlySerDes());
        operation.execute();
        Thread.sleep(200);

        assertEquals("result", operation.get());
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.SUCCEED));
        verify(executionManager, never())
                .sendOperationUpdate(argThat(update -> update.action() == OperationAction.FAIL));
    }

    /** Child skips failure checkpoint when parent operation has already completed. */
    @Test
    void childSkipsFailureCheckpointWhenParentAlreadyCompleted() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);

        var parent = new CompletedParentOperation(durableContext);

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

    private static final class CompletedParentOperation extends BasePrimitive {
        private CompletedParentOperation(DurableContextImpl durableContext) {
            super(
                    OperationIdentifier.of("parent", "parent", OperationSubType.RUN_IN_CHILD_CONTEXT),
                    durableContext,
                    null);
        }

        @Override
        protected void start() {}

        @Override
        protected void replay(Operation existing) {}

        @Override
        protected boolean isOperationCompleted() {
            return true;
        }
    }
}
