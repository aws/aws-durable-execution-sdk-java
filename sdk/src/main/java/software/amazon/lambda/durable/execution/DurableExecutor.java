// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.exception.RetryablePayloadOffloadException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.logging.DurableLogger;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.DurableExecutionOutput;
import software.amazon.lambda.durable.model.InvocationSource;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.PayloadOffloaders;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.offload.internal.ChainedInvokeOutputFrame;
import software.amazon.lambda.durable.offload.internal.ChainedInvokePayloadFrame;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.PluginInfoConverter;
import software.amazon.lambda.durable.plugin.PluginRunner;
import software.amazon.lambda.durable.util.ExceptionHelper;

/**
 * Orchestrates the lifecycle of a durable execution.
 *
 * <p>Handles deserialization of user input, invocation of the user handler within a {@link DurableContext}, and
 * production of the {@link DurableExecutionOutput} (success, failure, or pending suspension).
 */
public class DurableExecutor {
    private static final String ROOT_THREAD_ID = null;
    private static final Logger logger = LoggerFactory.getLogger(DurableExecutor.class);

    // Lambda response size limit is 6MB minus small epsilon for envelope
    private static final int LAMBDA_RESPONSE_SIZE_LIMIT = 6 * 1024 * 1024 - 50;

    private DurableExecutor() {}

    public static <I, O> DurableExecutionOutput execute(
            DurableExecutionInput input,
            Context lambdaContext,
            TypeToken<I> inputType,
            BiFunction<I, DurableContext, O> handler,
            DurableConfig config) {
        var pluginRunner = config.getPluginRunner();
        try (var executionManager = new ExecutionManager(input, config, lambdaContext)) {
            var isFirstInvocation = !executionManager.isReplaying();
            var requestId = lambdaContext != null ? lambdaContext.getAwsRequestId() : null;
            var executionArn = input.durableExecutionArn();
            var frameChainedInvokeOutput = shouldFrameChainedInvokeOutput(executionManager, config);
            var outputOffloader =
                    input.invocationSource() == InvocationSource.CHAINED_INVOKE && !frameChainedInvokeOutput
                            ? PayloadOffloaders.disabled()
                            : config.getPayloadOffloader();

            executionManager.registerActiveThread(null);
            // Captured for onInvocationEnd, which runs outside the handler thread below.
            var pluginExecutionInput = new AtomicReference<>();
            var handlerFuture = CompletableFuture.supplyAsync(
                    () -> {
                        executionManager.setCurrentThreadContext(new ThreadContext(null, ThreadType.CONTEXT));

                        // Deserialize once and share the value with the plugin hooks and the handler below. A second
                        // deserialization would double the cost, hand plugins a different object than the handler, and
                        // re-run any side effects in a stateful custom SerDes. A failure is captured rather than thrown
                        // so onInvocationStart still fires before it surfaces, keeping the start/end hooks paired.
                        // SerDes is a public extension point whose deserialize declares no checked exceptions, so an
                        // implementation may sneaky-throw one; capture every Throwable and rethrow it unchanged.
                        I userInput = null;
                        Throwable inputFailure = null;
                        try {
                            userInput = extractUserInput(executionManager, config, inputType);
                        } catch (Throwable t) {
                            inputFailure = t;
                        }
                        pluginExecutionInput.set(userInput);

                        // onInvocationStart runs on the user thread so plugins can
                        // inject ThreadLocal objects, update MDC, etc.
                        // executionStartTime comes from the initial EXECUTION operation in the first backend event.
                        if (!pluginRunner.isEmpty()) {
                            pluginRunner.onInvocationStart(new InvocationInfo(
                                    requestId,
                                    executionArn,
                                    isFirstInvocation,
                                    executionManager.getExecutionOperation().startTimestamp(),
                                    userInput,
                                    PluginInfoConverter.toOperationItemMap(
                                            executionManager.getOperationsSnapshot(),
                                            executionManager.getInitialOperationIds()),
                                    PluginInfoConverter.toOperationItemMap(
                                            executionManager.getUpdatedOperationsSnapshot(),
                                            executionManager.getInitialOperationIds())));
                        }
                        if (inputFailure != null) {
                            ExceptionHelper.sneakyThrow(inputFailure);
                        }

                        var context = DurableContextImpl.createRootContext(executionManager, config, lambdaContext);
                        DurableContextImpl.setCurrentContext(context);
                        // use a try-with-resources to clear logger properties
                        try (var ignored = DurableLogger.attachContext()) {
                            return handler.apply(userInput, context);
                        }
                    },
                    config.getExecutorService()); // Get executor from config for running user code

            // Execute the handlerFuture in ExecutionManager. If it completes successfully, the output of user function
            // will be returned. Otherwise, it will complete exceptionally with a SuspendExecutionException or a
            // failure.
            try {
                return executionManager
                        .runUntilCompleteOrSuspend(handlerFuture)
                        .handle((result, ex) -> {
                            if (ex != null) {
                                return handleExecutionFailure(
                                        ExceptionHelper.unwrapCompletableFuture(ex),
                                        pluginRunner,
                                        executionManager,
                                        config,
                                        requestId,
                                        executionArn,
                                        isFirstInvocation,
                                        frameChainedInvokeOutput,
                                        outputOffloader,
                                        pluginExecutionInput.get());
                            }
                            final String outputPayload;
                            try {
                                outputPayload = executionManager
                                        .getPayloadCodec()
                                        .serialize(
                                                result,
                                                config.getSerDes(),
                                                outputOffloader,
                                                executionContext(executionManager, SerDesPayloadKind.OUTPUT));
                            } catch (PayloadOffloadException outputFailure) {
                                return handleExecutionFailure(
                                        outputFailure,
                                        pluginRunner,
                                        executionManager,
                                        config,
                                        requestId,
                                        executionArn,
                                        isFirstInvocation,
                                        frameChainedInvokeOutput,
                                        outputOffloader,
                                        pluginExecutionInput.get());
                            }

                            // User handler and output serialization completed successfully. Infrastructure failures
                            // while
                            // publishing a large root result must escape for invocation retry rather than being
                            // converted
                            // into a terminal user failure.
                            logger.debug("Execution completed");
                            var responsePayload = frameChainedInvokeOutput
                                    ? ChainedInvokeOutputFrame.encode(
                                            outputPayload, PayloadCodec.isOffloadEnvelope(outputPayload))
                                    : outputPayload;
                            var output = DurableExecutionOutput.success(
                                    handleLargePayload(executionManager, responsePayload));
                            fireOnInvocationEnd(
                                    pluginRunner,
                                    executionManager,
                                    requestId,
                                    executionArn,
                                    isFirstInvocation,
                                    InvocationStatus.SUCCEEDED,
                                    null,
                                    pluginExecutionInput.get(),
                                    result);
                            return output;
                        })
                        .join();
            } catch (CompletionException e) {
                // unwrap the CompletionException and rethrow the wrapped exception
                ExceptionHelper.sneakyThrow(ExceptionHelper.unwrapCompletableFuture(e));
                return null;
            }
        }
    }

    private static DurableExecutionOutput handleExecutionFailure(
            Throwable cause,
            PluginRunner pluginRunner,
            ExecutionManager executionManager,
            DurableConfig config,
            String requestId,
            String executionArn,
            boolean isFirstInvocation,
            boolean frameChainedInvokeOutput,
            PayloadOffloader outputOffloader,
            Object executionInput) {
        if (cause instanceof SuspendExecutionException) {
            fireOnInvocationEnd(
                    pluginRunner,
                    executionManager,
                    requestId,
                    executionArn,
                    isFirstInvocation,
                    InvocationStatus.PENDING,
                    null,
                    executionInput,
                    null);
            return DurableExecutionOutput.pending();
        }

        if (cause instanceof RetryablePayloadOffloadException retryablePayloadOffloadException) {
            fireOnInvocationEnd(
                    pluginRunner,
                    executionManager,
                    requestId,
                    executionArn,
                    isFirstInvocation,
                    InvocationStatus.RETRYING,
                    cause,
                    executionInput,
                    null);
            throw retryablePayloadOffloadException;
        }

        if (cause instanceof UnrecoverableDurableExecutionException unrecoverable && unrecoverable.isRetryable()) {
            fireOnInvocationEnd(
                    pluginRunner,
                    executionManager,
                    requestId,
                    executionArn,
                    isFirstInvocation,
                    InvocationStatus.RETRYING,
                    cause,
                    executionInput,
                    null);
            throw unrecoverable;
        }

        logger.debug("Execution failed: {}", cause.getMessage());
        Throwable reportedCause = cause;
        EncodedError encodedError;
        try {
            encodedError = buildErrorObject(cause, executionManager, config, outputOffloader);
        } catch (PayloadOffloadException payloadFailure) {
            if (payloadFailure instanceof RetryablePayloadOffloadException retryablePayloadFailure) {
                fireOnInvocationEnd(
                        pluginRunner,
                        executionManager,
                        requestId,
                        executionArn,
                        isFirstInvocation,
                        InvocationStatus.RETRYING,
                        retryablePayloadFailure,
                        executionInput,
                        null);
                throw retryablePayloadFailure;
            }
            reportedCause = payloadFailure;
            try {
                encodedError = buildErrorObject(payloadFailure, executionManager, config, outputOffloader);
            } catch (Throwable encodingFailure) {
                return rethrowEncodingFailureAfterInvocationEnd(
                        encodingFailure,
                        cause,
                        pluginRunner,
                        executionManager,
                        requestId,
                        executionArn,
                        isFirstInvocation,
                        executionInput);
            }
        } catch (Throwable encodingFailure) {
            return rethrowEncodingFailureAfterInvocationEnd(
                    encodingFailure,
                    cause,
                    pluginRunner,
                    executionManager,
                    requestId,
                    executionArn,
                    isFirstInvocation,
                    executionInput);
        }
        fireOnInvocationEnd(
                pluginRunner,
                executionManager,
                requestId,
                executionArn,
                isFirstInvocation,
                InvocationStatus.FAILED,
                reportedCause,
                executionInput,
                null);
        var errorObject = frameChainedInvokeOutput ? frameChainedInvokeError(encodedError) : encodedError.error();
        return DurableExecutionOutput.failure(errorObject);
    }

    private static <T> T rethrowEncodingFailureAfterInvocationEnd(
            Throwable encodingFailure,
            Throwable originalCause,
            PluginRunner pluginRunner,
            ExecutionManager executionManager,
            String requestId,
            String executionArn,
            boolean isFirstInvocation,
            Object executionInput) {
        fireOnInvocationEnd(
                pluginRunner,
                executionManager,
                requestId,
                executionArn,
                isFirstInvocation,
                InvocationStatus.FAILED,
                originalCause,
                executionInput,
                null);
        ExceptionHelper.sneakyThrow(encodingFailure);
        return null;
    }

    private static void fireOnInvocationEnd(
            PluginRunner pluginRunner,
            ExecutionManager executionManager,
            String requestId,
            String executionArn,
            boolean isFirstInvocation,
            InvocationStatus status,
            Throwable error,
            Object executionInput,
            Object executionResult) {
        if (pluginRunner.isEmpty()) {
            return;
        }
        pluginRunner.onInvocationEnd(new InvocationEndInfo(
                requestId,
                executionArn,
                isFirstInvocation,
                executionManager.getExecutionOperation().startTimestamp(),
                PluginInfoConverter.toOperationItemMap(
                        executionManager.getOperationsSnapshot(), executionManager.getInitialOperationIds()),
                status,
                error,
                executionInput,
                executionResult));
    }

    private static String handleLargePayload(ExecutionManager executionManager, String outputPayload) {
        // Check if the serialized payload exceeds Lambda response size limit
        var payloadSize = outputPayload != null ? outputPayload.getBytes(StandardCharsets.UTF_8).length : 0;

        if (payloadSize > LAMBDA_RESPONSE_SIZE_LIMIT) {
            logger.debug(
                    "Response size ({} bytes) exceeds Lambda limit ({} bytes). Checkpointing result.",
                    payloadSize,
                    LAMBDA_RESPONSE_SIZE_LIMIT);

            // Checkpoint the large result and wait for it to complete
            executionManager
                    .sendOperationUpdate(OperationUpdate.builder()
                            .type(OperationType.EXECUTION)
                            .id(executionManager.getExecutionOperation().id())
                            .action(OperationAction.SUCCEED)
                            .payload(outputPayload)
                            .build())
                    .join();

            // Return empty result, we checkpointed the data manually
            logger.debug("Execution completed (large response checkpointed)");
            return "";
        }

        // If response size is acceptable, return the result directly
        return outputPayload;
    }

    private static EncodedError buildErrorObject(
            Throwable e, ExecutionManager executionManager, DurableConfig config, PayloadOffloader outputOffloader) {
        // exceptions thrown from operations, e.g. Step
        if (e instanceof DurableOperationException durableOperationException) {
            var error = durableOperationException.getErrorObject();
            if (error == null || error.errorData() == null) {
                return new EncodedError(error, false);
            }
            var targetContext = executionContext(executionManager, SerDesPayloadKind.EXCEPTION);
            final String errorData;
            final boolean usesPayloadCodec;
            if (durableOperationException.getPayloadOffloadContext() == null) {
                errorData = executionManager
                        .getPayloadCodec()
                        .offloadSerializedPayload(error.errorData(), outputOffloader, targetContext);
                usesPayloadCodec = hasActivePayloadOffloader(outputOffloader);
            } else {
                errorData = executionManager
                        .getPayloadCodec()
                        .rebindSerializedPayload(
                                error.errorData(),
                                durableOperationException.getPayloadOffloader(),
                                durableOperationException.getPayloadOffloadContext(),
                                outputOffloader,
                                targetContext);
                usesPayloadCodec = PayloadCodec.isOffloadEnvelope(errorData);
            }
            return new EncodedError(error.toBuilder().errorData(errorData).build(), usesPayloadCodec);
        }
        if (e instanceof UnrecoverableDurableExecutionException unrecoverableDurableExecutionException) {
            return new EncodedError(unrecoverableDurableExecutionException.getErrorObject(), false);
        }
        // exceptions thrown from non-operation code
        final String errorData;
        final boolean usesPayloadCodec;
        if (e instanceof PayloadOffloadException) {
            errorData = config.getSerDes().serialize(e);
            usesPayloadCodec = false;
        } else {
            errorData = executionManager
                    .getPayloadCodec()
                    .serialize(
                            e,
                            config.getSerDes(),
                            outputOffloader,
                            executionContext(executionManager, SerDesPayloadKind.EXCEPTION));
            usesPayloadCodec = PayloadCodec.isOffloadEnvelope(errorData);
        }
        return new EncodedError(
                ErrorObject.builder()
                        .errorType(e.getClass().getName())
                        .errorMessage(e.getMessage())
                        .errorData(errorData)
                        .stackTrace(ExceptionHelper.serializeStackTrace(e.getStackTrace()))
                        .build(),
                usesPayloadCodec);
    }

    private static ErrorObject frameChainedInvokeError(EncodedError encodedError) {
        var error = encodedError.error();
        if (error == null || error.errorData() == null) {
            return error;
        }
        return error.toBuilder()
                .errorData(ChainedInvokeOutputFrame.encode(error.errorData(), encodedError.usesPayloadCodec()))
                .build();
    }

    private static boolean shouldFrameChainedInvokeOutput(ExecutionManager executionManager, DurableConfig config) {
        var details = executionManager.getExecutionOperation().executionDetails();
        return config.shouldUsePayloadOffloaderForChainedInvokePayloads()
                && details != null
                && ChainedInvokePayloadFrame.isFramed(details.inputPayload());
    }

    private static boolean hasActivePayloadOffloader(PayloadOffloader offloader) {
        return offloader != null && !PayloadOffloaders.isDisabled(offloader);
    }

    private record EncodedError(ErrorObject error, boolean usesPayloadCodec) {}

    private static <I> I extractUserInput(
            ExecutionManager executionManager, DurableConfig config, TypeToken<I> inputType) {
        var executionOp = executionManager.getExecutionOperation();
        if (executionOp.executionDetails() == null) {
            throw new IllegalDurableOperationException("EXECUTION operation missing executionDetails");
        }

        var inputPayload = executionOp.executionDetails().inputPayload();
        if (!config.shouldUsePayloadOffloaderForChainedInvokePayloads()
                || !ChainedInvokePayloadFrame.isFramed(inputPayload)) {
            return config.getSerDes().deserialize(inputPayload, inputType);
        }
        inputPayload = ChainedInvokePayloadFrame.decode(inputPayload);
        return executionManager
                .getPayloadCodec()
                .deserialize(
                        inputPayload,
                        inputType,
                        config.getSerDes(),
                        config.getPayloadOffloader(),
                        executionContext(executionManager, SerDesPayloadKind.INPUT));
    }

    private static PayloadOffloadContext executionContext(
            ExecutionManager executionManager, SerDesPayloadKind payloadKind) {
        var operation = executionManager.getExecutionOperation();
        return PayloadOffloadContext.forExecution(
                executionManager.getDurableExecutionArn(), operation.id(), operation.name(), payloadKind);
    }

    /**
     * Wraps a user handler in a RequestHandler that can be used by the Lambda runtime.
     *
     * @param inputType the type token for the input
     * @param handler the handler function
     * @param config the durable config
     * @return a request handler that executes the durable function
     * @param <I> the type of the input
     * @param <O> the type of the output
     */
    public static <I, O> RequestHandler<DurableExecutionInput, DurableExecutionOutput> wrap(
            TypeToken<I> inputType, BiFunction<I, DurableContext, O> handler, DurableConfig config) {
        return (input, context) -> execute(input, context, inputType, handler, config);
    }
}
