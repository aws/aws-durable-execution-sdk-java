// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.primitive;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.extension.ExtensionChildOperationSummary;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFailure;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.model.DeserializedOperationResult;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Manages the lifecycle of a child execution context.
 *
 * <p>A child context runs a user function in a separate thread with its own operation counter and checkpoint log.
 * Operations within the child context use the child's context ID as their parentId.
 *
 * <p>When created with a parent operation, the child skips checkpointing if that parent has already completed.
 */
public class ChildContextPrimitive<T> extends SerializablePrimitive<T> {

    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

    private final Function<DurableContext, T> function;
    private final ExtensionContextFunction<T> extensionFunction;
    private final ExtensionContextConfig extensionConfig;
    private final AtomicBoolean replayChildren = new AtomicBoolean(false);
    private final AtomicReference<T> replayState = new AtomicReference<>(null);
    private final AtomicReference<DeserializedOperationResult<T>> cachedOperationResult = new AtomicReference<>(null);

    // child context for RunInChildContext
    public ChildContextPrimitive(
            OperationIdentifier operationIdentifier,
            Function<DurableContext, T> function,
            TypeToken<T> resultTypeToken,
            RunInChildContextConfig config,
            DurableContextImpl durableContext) {
        this(operationIdentifier, function, resultTypeToken, config, durableContext, null);
    }

    // child context with a late-checkpoint owner
    public ChildContextPrimitive(
            OperationIdentifier operationIdentifier,
            Function<DurableContext, T> function,
            TypeToken<T> resultTypeToken,
            RunInChildContextConfig config,
            DurableContextImpl durableContext,
            BasePrimitive parentOperation) {
        super(
                operationIdentifier,
                resultTypeToken,
                config.serDes(),
                durableContext,
                parentOperation,
                config.isVirtual());
        this.function = function;
        this.extensionFunction = null;
        this.extensionConfig = null;
    }

    public ChildContextPrimitive(
            OperationIdentifier operationIdentifier,
            ExtensionContextFunction<T> function,
            TypeToken<T> resultTypeToken,
            ExtensionContextConfig config,
            DurableContextImpl durableContext) {
        this(operationIdentifier, function, resultTypeToken, config, durableContext, null);
    }

    public ChildContextPrimitive(
            OperationIdentifier operationIdentifier,
            ExtensionContextFunction<T> function,
            TypeToken<T> resultTypeToken,
            ExtensionContextConfig config,
            DurableContextImpl durableContext,
            BasePrimitive parentOperation) {
        super(
                operationIdentifier,
                resultTypeToken,
                config.serDes(),
                durableContext,
                parentOperation,
                config.isVirtual());
        this.function = null;
        this.extensionFunction = function;
        this.extensionConfig = config;
    }

    /** Starts the operation. */
    @Override
    protected void start() {
        // First execution: fire-and-forget START checkpoint, then run
        if (!isVirtual) {
            sendOperationUpdateAsync(OperationUpdate.builder().action(OperationAction.START));
        }
        executeChildContext();
    }

    /** Replays the operation. */
    @Override
    protected void replay(Operation existing) {
        switch (existing.status()) {
            case SUCCEEDED -> {
                if (existing.contextDetails() != null
                        && Boolean.TRUE.equals(existing.contextDetails().replayChildren())) {
                    replayChildren.set(true);
                    if (extensionFunction != null) {
                        replayState.set(
                                deserializeResult(existing.contextDetails().result()));
                    }
                    executeChildContext();
                } else {
                    markAlreadyCompleted();
                }
            }
            case FAILED -> markAlreadyCompleted();
            case STARTED -> executeChildContext();
            default ->
                throw terminateExecutionWithIllegalDurableOperationException(
                        "Unexpected child context status: " + existing.status());
        }
    }

    private void executeChildContext() {
        // The operationId is already globally unique (prefixed by parent context path via
        // DurableContext.nextOperationId), so we use it directly as the contextId.
        // E.g., first level child context "hash(1)",
        //       second level child context "hash(hash(1)-2)",
        //       third level child context "hash(hash(hash(1)-2)-1)".
        var contextId = getOperationId();

        Runnable userHandler = () -> {
            // use a try-with-resources to
            // - add thread id/type to thread local when the step starts
            // - clear logger properties when the step finishes
            //
            // A parent operation may own late-checkpoint suppression for this child.
            var childContext = createChildContext(contextId);
            try (var ignoredContext = DurableContextImpl.attachCurrentContext(childContext);
                    var ignoredLogger = DurableLogger.attachContext()) {
                try {
                    executeFunction(childContext);
                } catch (Throwable e) {
                    handleChildContextFailure(e);
                }
            }
        };

        // Execute user provided child context code in user-configured executor
        runUserHandler(userHandler, ThreadType.CONTEXT);
    }

    private DurableContextImpl createChildContext(String contextId) {
        if (extensionConfig != null && extensionConfig.suppressLateChildCheckpoints()) {
            return getContext().createChildContext(contextId, getName(), isVirtual, this);
        }
        return getContext().createChildContext(contextId, getName(), isVirtual);
    }

    private void executeFunction(DurableContextImpl childContext) {
        if (extensionFunction == null) {
            var result = runUserFunction(null, () -> function.apply(childContext));
            handleChildContextSuccess(result);
            return;
        }

        try (var ignoredReplayContext = ExtensionContextReplayContext.attach(replayChildren.get(), replayState.get())) {
            var result = extensionConfig.emitUserFunctionEvents()
                    ? runUserFunction(null, extensionFunction::apply)
                    : extensionFunction.apply();
            handleExtensionContextSuccess(
                    Objects.requireNonNull(result, "Extension context function result cannot be null"));
        }
    }

    private void handleChildContextSuccess(T result) {
        var serializedResult = serializeAndDeserializeResult(result);

        if (shouldSkipCheckpoint()) {
            cacheSuccessAndComplete(serializedResult.deserialized());
        } else {
            checkpointSuccess(serializedResult.deserialized(), serializedResult.serialized());
        }
    }

    private void handleExtensionContextSuccess(ExtensionContextResult<T> result) {
        var serializedResult = serializeAndDeserializeResult(result.result());
        if (shouldSkipCheckpoint()) {
            cacheSuccessAndComplete(serializedResult.deserialized());
            return;
        }

        var resultBytes = serializedSize(serializedResult.serialized());
        if (result.shouldReplayChildren(resultBytes)) {
            var serializedReplayState = serializeAndDeserializeResult(result.replayState());
            cachedOperationResult.set(DeserializedOperationResult.succeeded(serializedResult.deserialized()));
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .payload(serializedReplayState.serialized())
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        } else {
            sendOperationUpdate(
                    OperationUpdate.builder().action(OperationAction.SUCCEED).payload(serializedResult.serialized()));
        }
    }

    private boolean shouldSkipCheckpoint() {
        return replayChildren.get() || isVirtual || parentOperation != null && parentOperation.isOperationCompleted();
    }

    private void cacheSuccessAndComplete(T result) {
        cachedOperationResult.set(DeserializedOperationResult.succeeded(result));
        if (isVirtual) {
            fireOnOperationEnd(null, null, false);
        }
        markAlreadyCompleted();
    }

    private void checkpointSuccess(T result, String serialized) {
        if (serializedSize(serialized) < LARGE_RESULT_THRESHOLD) {
            sendOperationUpdate(
                    OperationUpdate.builder().action(OperationAction.SUCCEED).payload(serialized));
        } else {
            // Large result: checkpoint with empty payload + ReplayChildren flag.
            // Store the result so get() can return it directly without deserializing the empty payload.
            cachedOperationResult.set(DeserializedOperationResult.succeeded(result));
            sendOperationUpdate(OperationUpdate.builder()
                    .action(OperationAction.SUCCEED)
                    .payload("")
                    .contextOptions(
                            ContextOptions.builder().replayChildren(true).build()));
        }
    }

    private int serializedSize(String serialized) {
        return serialized == null ? 0 : serialized.getBytes(StandardCharsets.UTF_8).length;
    }

    private void handleChildContextFailure(Throwable exception) {
        exception = ExceptionHelper.unwrapCompletableFuture(exception);
        if (exception instanceof SuspendExecutionException suspendExecutionException) {
            // Rethrow Error immediately — do not checkpoint
            throw suspendExecutionException;
        }
        if (exception instanceof UnrecoverableDurableExecutionException unrecoverableDurableExecutionException) {
            // terminate the execution and throw the exception if it's not recoverable
            throw terminateExecution(unrecoverableDurableExecutionException);
        }

        final ErrorObject errorObject;
        if (exception instanceof DurableOperationException opEx) {
            errorObject = opEx.getErrorObject();
        } else {
            errorObject = serializeException(exception);
        }

        var op = createVirtualOperation(errorObject);
        cachedOperationResult.set(DeserializedOperationResult.failed(translateException(op, errorObject)));

        // Skip checkpointing if
        // - the owning parent operation has already completed, preventing a late child checkpoint.
        // - this child is not a direct child of a parent context (i.e. nestingType == FLAT), such as a parallel branch.
        if ((parentOperation != null && parentOperation.isOperationCompleted()) || isVirtual) {
            if (isVirtual) {
                fireOnOperationEnd(null, exception, false);
            }
            markAlreadyCompleted();
            return;
        }

        sendOperationUpdate(
                OperationUpdate.builder().action(OperationAction.FAIL).error(errorObject));
    }

    @Override
    public T get() {
        var op = waitForOperationCompletion();
        if (cachedOperationResult.get() != null) {
            // we have a result, just return it directly
            return cachedOperationResult.get().get();
        }

        if (op.status() == OperationStatus.SUCCEEDED) {
            var contextDetails = op.contextDetails();
            var result = (contextDetails != null) ? contextDetails.result() : null;
            return deserializeResult(result);
        } else {
            var contextDetails = op.contextDetails();
            var errorObject = (contextDetails != null) ? contextDetails.error() : null;

            ExceptionHelper.sneakyThrow(translateException(op, errorObject));
            return null;
        }
    }

    private Throwable translateException(Operation op, ErrorObject errorObject) {
        // Attempt to reconstruct and throw the original exception
        Throwable original = deserializeException(errorObject);
        if (original != null) {
            return original;
        }

        if (extensionConfig != null && extensionConfig.errorHandler() != null) {
            var failure = new ExtensionContextFailure(op, null, getChildOperationSummaries());
            return Objects.requireNonNull(
                    extensionConfig.errorHandler().translate(failure),
                    "Extension context error handler result cannot be null");
        }

        return new ChildContextFailedException(op);
    }

    private List<ExtensionChildOperationSummary> getChildOperationSummaries() {
        return getChildOperations().stream()
                .map(ExtensionChildOperationSummary::new)
                .toList();
    }

    private Operation createVirtualOperation(ErrorObject errorObject) {
        return Operation.builder()
                .id(getOperationId())
                .name(getName())
                .type(OperationType.CONTEXT)
                .subType(getSubTypeValue())
                .status(OperationStatus.FAILED)
                .contextDetails(ContextDetails.builder().error(errorObject).build())
                .build();
    }
}
