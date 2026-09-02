// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.PayloadCodec;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.ConcurrencyCompletionStatus;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.offload.OffloadedPayload;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

/** Unit tests for ChildContextOperation. */
class ChildContextOperationTest {

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

    private ChildContextOperation<String> createOperation(Function<DurableContext, String> func) {
        return createOperation(func, SERDES);
    }

    private ChildContextOperation<String> createOperation(Function<DurableContext, String> func, SerDes serDes) {
        return new ChildContextOperation<>(
                OPERATION_IDENTIFIER,
                func,
                TypeToken.get(String.class),
                RunInChildContextConfig.builder().serDes(serDes).build(),
                durableContext);
    }

    private ChildContextOperation<String> createVirtualOperation(Function<DurableContext, String> func) {
        return createVirtualOperation(func, SERDES);
    }

    private ChildContextOperation<String> createVirtualOperation(Function<DurableContext, String> func, SerDes serDes) {
        return new ChildContextOperation<>(
                OPERATION_IDENTIFIER,
                func,
                TypeToken.get(String.class),
                RunInChildContextConfig.builder().serDes(serDes).isVirtual(true).build(),
                durableContext);
    }

    private ChildContextOperation<String> createOperationWithParent(
            Function<DurableContext, String> func, ConcurrencyOperation<?> parent) {
        return createOperationWithParent(func, parent, false);
    }

    private ChildContextOperation<String> createOperationWithParent(
            Function<DurableContext, String> func, ConcurrencyOperation<?> parent, boolean isVirtual) {
        return createOperationWithParent(func, parent, isVirtual, null);
    }

    private ChildContextOperation<String> createOperationWithParent(
            Function<DurableContext, String> func,
            ConcurrencyOperation<?> parent,
            boolean isVirtual,
            PayloadOffloader payloadOffloader) {
        return new ChildContextOperation<>(
                OPERATION_IDENTIFIER,
                func,
                TypeToken.get(String.class),
                RunInChildContextConfig.builder()
                        .serDes(SERDES)
                        .payloadOffloader(payloadOffloader)
                        .isVirtual(isVirtual)
                        .build(),
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

    @Test
    void parentRejectedNonVirtualSuccessFiresOperationEnd() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);
        var operationEnd = new AtomicReference<OperationEndInfo>();
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .withPlugins(new DurableExecutionPlugin() {
                            @Override
                            public void onOperationEnd(OperationEndInfo info) {
                                operationEnd.set(info);
                            }
                        })
                        .build());
        var parent = new RecordingCompletionParent(durableContext);
        parent.beginCompletion();
        var operation = createOperationWithParent(ctx -> "result", parent);

        operation.execute();
        operation.getRunningUserHandler().get(5, TimeUnit.SECONDS);

        assertNotNull(operationEnd.get());
        assertNull(operationEnd.get().error());
    }

    @Test
    void parentRejectedNonVirtualFailureFiresOperationEnd() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);
        var operationEnd = new AtomicReference<OperationEndInfo>();
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .withPlugins(new DurableExecutionPlugin() {
                            @Override
                            public void onOperationEnd(OperationEndInfo info) {
                                operationEnd.set(info);
                            }
                        })
                        .build());
        var parent = new RecordingCompletionParent(durableContext);
        parent.beginCompletion();
        var branchFailure = new IllegalStateException("branch failed");
        var operation = createOperationWithParent(
                ctx -> {
                    throw branchFailure;
                },
                parent);

        operation.execute();
        operation.getRunningUserHandler().get(5, TimeUnit.SECONDS);

        assertNotNull(operationEnd.get());
        assertSame(branchFailure, operationEnd.get().error());
    }

    @Test
    void latePayloadFailureIsSkippedAfterParentCompletionStarts() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);
        var parent = new BlockingCompletionParent(durableContext);
        var parentCompletion = CompletableFuture.runAsync(parent::beginCompletion);
        assertTrue(parent.awaitCompletionStarted());

        var lateFailure = new PayloadOffloadException("late payload failure");
        try {
            var operation = createOperationWithParent(
                    ctx -> {
                        throw lateFailure;
                    },
                    parent);
            operation.execute();

            Thread.sleep(100);
            assertFalse(operation.getCompletionFuture().isDone());
            parent.releaseCompletion();
            parentCompletion.get(5, TimeUnit.SECONDS);
            operation.getCompletionFuture().get(5, TimeUnit.SECONDS);

            assertTrue(operation.isOperationCompleted());
            assertSame(lateFailure, assertThrows(PayloadOffloadException.class, operation::get));
            verify(executionManager, never()).failInvocation(any());
        } finally {
            parent.releaseCompletion();
            parentCompletion.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void lateVirtualPayloadFailureFiresOperationEndAndRemainsObservable() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);
        var operationEnd = new AtomicReference<OperationEndInfo>();
        var plugin = new DurableExecutionPlugin() {
            @Override
            public void onOperationEnd(OperationEndInfo info) {
                if ("1".equals(info.id())) {
                    operationEnd.set(info);
                }
            }
        };
        when(durableContext.getDurableConfig())
                .thenReturn(DurableConfig.builder()
                        .withExecutorService(Executors.newCachedThreadPool())
                        .withPlugins(plugin)
                        .build());
        var parent = new BlockingCompletionParent(durableContext);
        var parentCompletion = CompletableFuture.runAsync(parent::beginCompletion);
        assertTrue(parent.awaitCompletionStarted());
        var lateFailure = new PayloadOffloadException("late virtual payload failure");

        try {
            var operation = createOperationWithParent(
                    ctx -> {
                        throw lateFailure;
                    },
                    parent,
                    true);
            operation.execute();

            Thread.sleep(100);
            assertFalse(operation.getCompletionFuture().isDone());
            parent.releaseCompletion();
            parentCompletion.get(5, TimeUnit.SECONDS);
            assertSame(lateFailure, assertThrows(PayloadOffloadException.class, operation::get));
            assertNotNull(operationEnd.get());
            assertSame(lateFailure, operationEnd.get().error());
            verify(executionManager, never()).failInvocation(any());
        } finally {
            parent.releaseCompletion();
            parentCompletion.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void lateForwardedFailureDoesNotLoadUnavailableSourcePayload() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);
        var parent = new BlockingCompletionParent(durableContext);
        var parentCompletion = CompletableFuture.runAsync(parent::beginCompletion);
        assertTrue(parent.awaitCompletionStarted());
        var loadCount = new AtomicInteger();
        var sourceOffloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                return OffloadedPayload.reference("memory://source-error", null);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                loadCount.incrementAndGet();
                throw new PayloadOffloadException("source unavailable");
            }
        };
        var sourceContext = PayloadOffloadContext.forOperation(
                "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/name/id",
                OperationIdentifier.of("source", "source", OperationSubType.STEP),
                null,
                SerDesPayloadKind.EXCEPTION,
                1);
        var sourcePayload = new PayloadCodec(null)
                .serialize(new IllegalStateException("source"), SERDES, sourceOffloader, sourceContext);
        var sourceError = ErrorObject.builder()
                .errorType(IllegalStateException.class.getName())
                .errorMessage("source")
                .errorData(sourcePayload)
                .build();
        var sourceOperation = Operation.builder()
                .id("source")
                .type(OperationType.STEP)
                .status(OperationStatus.FAILED)
                .build();
        var sourceFailure = new DurableOperationException(sourceOperation, sourceError)
                .withPayloadSource(sourceOffloader, sourceContext);

        try {
            var operation = createOperationWithParent(
                    ctx -> {
                        throw sourceFailure;
                    },
                    parent);
            operation.execute();

            Thread.sleep(100);
            assertFalse(operation.getCompletionFuture().isDone());
            parent.releaseCompletion();
            parentCompletion.get(5, TimeUnit.SECONDS);

            assertSame(sourceFailure, assertThrows(DurableOperationException.class, operation::get));
            assertEquals(0, loadCount.get());
            verify(executionManager, never()).failInvocation(any());
        } finally {
            parent.releaseCompletion();
            parentCompletion.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void exceptionOffloadFailureClaimsParentBeforeEarlyCompletion() throws Exception {
        when(executionManager.getOperationAndUpdateReplayState("1")).thenReturn(null);
        when(executionManager.getDurableExecutionArn())
                .thenReturn("arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST/durable-execution/name/id");
        when(executionManager.getPayloadCodec()).thenReturn(new PayloadCodec(null));
        var invocationFailed = new AtomicBoolean();
        doAnswer(invocation -> {
                    invocationFailed.set(true);
                    return null;
                })
                .when(executionManager)
                .failInvocation(any());
        when(executionManager.isExecutionCompletedExceptionally()).thenAnswer(invocation -> invocationFailed.get());

        var offloadStarted = new CountDownLatch(1);
        var releaseOffload = new CountDownLatch(1);
        var payloadFailure = new PayloadOffloadException("exception offload failed");
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                offloadStarted.countDown();
                try {
                    assertTrue(releaseOffload.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                throw payloadFailure;
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                throw new AssertionError("load should not be called");
            }
        };
        var parent = new RecordingCompletionParent(durableContext);
        var operation = createOperationWithParent(
                ctx -> {
                    throw new IllegalStateException("branch failed");
                },
                parent,
                false,
                offloader);

        try {
            operation.execute();
            assertTrue(offloadStarted.await(5, TimeUnit.SECONDS));

            var parentCompletion = CompletableFuture.runAsync(parent::beginCompletion);
            Thread.sleep(100);
            assertFalse(parentCompletion.isDone(), "Early completion must wait for child exception persistence");

            releaseOffload.countDown();
            operation.getRunningUserHandler().get(5, TimeUnit.SECONDS);
            parentCompletion.get(5, TimeUnit.SECONDS);

            verify(executionManager).failInvocation(same(payloadFailure));
            assertFalse(parent.isCompletionHandled());
        } finally {
            releaseOffload.countDown();
        }
    }

    private static final class BlockingCompletionParent extends ConcurrencyOperation<Void> {
        private final CountDownLatch completionStarted = new CountDownLatch(1);
        private final CountDownLatch releaseCompletion = new CountDownLatch(1);

        private BlockingCompletionParent(DurableContextImpl durableContext) {
            super(
                    OperationIdentifier.of("parent", "parent", OperationSubType.PARALLEL),
                    TypeToken.get(Void.class),
                    SERDES,
                    durableContext,
                    1,
                    CompletionConfig.allSuccessful().completionDecisionFunction(),
                    NestingType.NESTED);
        }

        private void beginCompletion() {
            initiateCompletion(
                    CompletionConfig.CompletionDecision.complete(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED));
        }

        private boolean awaitCompletionStarted() throws InterruptedException {
            return completionStarted.await(5, TimeUnit.SECONDS);
        }

        private void releaseCompletion() {
            releaseCompletion.countDown();
        }

        @Override
        protected void handleCompletion(CompletionConfig.CompletionDecision completionDecision) {
            completionStarted.countDown();
            try {
                assertTrue(releaseCompletion.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }

        @Override
        protected void start() {}

        @Override
        protected void replay(Operation existing) {}

        @Override
        public Void get() {
            return null;
        }
    }

    private static final class RecordingCompletionParent extends ConcurrencyOperation<Void> {
        private final AtomicBoolean completionHandled = new AtomicBoolean();

        private RecordingCompletionParent(DurableContextImpl durableContext) {
            super(
                    OperationIdentifier.of("parent", "parent", OperationSubType.PARALLEL),
                    TypeToken.get(Void.class),
                    SERDES,
                    durableContext,
                    1,
                    CompletionConfig.allSuccessful().completionDecisionFunction(),
                    NestingType.NESTED);
        }

        private void beginCompletion() {
            initiateCompletion(
                    CompletionConfig.CompletionDecision.complete(ConcurrencyCompletionStatus.MIN_SUCCESSFUL_REACHED));
        }

        private boolean isCompletionHandled() {
            return completionHandled.get();
        }

        @Override
        protected void handleCompletion(CompletionConfig.CompletionDecision completionDecision) {
            completionHandled.set(true);
        }

        @Override
        protected void start() {}

        @Override
        protected void replay(Operation existing) {}

        @Override
        public Void get() {
            return null;
        }
    }
}
