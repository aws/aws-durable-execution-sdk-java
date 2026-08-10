// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context.extension;

import static software.amazon.lambda.durable.execution.ExecutionManager.isTerminalStatus;

import java.util.Objects;
import java.util.function.BiConsumer;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackSubmitterException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.StepFailedException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFailure;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Canonical implementation of the built-in wait-for-callback extension. */
public final class WaitForCallbackExtension {
    private static final String CALLBACK_SUFFIX = "-callback";
    private static final String SUBMITTER_SUFFIX = "-submitter";
    private static final int MAX_NAME_LENGTH = ParameterValidator.MAX_OPERATION_NAME_LENGTH
            - Math.max(CALLBACK_SUFFIX.length(), SUBMITTER_SUFFIX.length());

    private WaitForCallbackExtension() {}

    public static <T> DurableFuture<T> execute(
            ExtensionContext context,
            String name,
            TypeToken<T> resultType,
            BiConsumer<String, StepContext> submitter,
            WaitForCallbackConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(resultType, "resultType cannot be null");
        Objects.requireNonNull(submitter, "submitter cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name, MAX_NAME_LENGTH);

        var parent = context.reserve(name);
        return parent.runInChildContextAsync(
                OperationSubType.WAIT_FOR_CALLBACK.getValue(),
                resultType,
                () -> executeInChildContext(name, resultType, submitter, config),
                extensionConfig(config));
    }

    private static <T> ExtensionContextResult<T> executeInChildContext(
            String name,
            TypeToken<T> resultType,
            BiConsumer<String, StepContext> submitter,
            WaitForCallbackConfig config) {
        var child = ExtensionContext.getCurrentContext();
        var callback = child.reserve(name + CALLBACK_SUFFIX).createCallback(resultType, config.callbackConfig());
        child.reserve(name + SUBMITTER_SUFFIX)
                .step(
                        Void.class,
                        () -> {
                            submitter.accept(callback.callbackId(), StepContext.getCurrentContext());
                            return null;
                        },
                        config.stepConfig());
        return ExtensionContextResult.completed(callback.get());
    }

    private static ExtensionContextConfig extensionConfig(WaitForCallbackConfig config) {
        return ExtensionContextConfig.builder()
                .childContextConfig(RunInChildContextConfig.builder()
                        .serDes(config.stepConfig().serDes())
                        .build())
                .errorHandler(WaitForCallbackExtension::translateFailure)
                .build();
    }

    private static Throwable translateFailure(ExtensionContextFailure failure) {
        var callback = findChild(failure, OperationType.CALLBACK);
        var submitter = findChild(failure, OperationType.STEP);
        if (callback != null && isTerminalStatus(callback.status())) {
            if (callback.status() == OperationStatus.FAILED) {
                return new CallbackFailedException(callback);
            }
            if (callback.status() == OperationStatus.TIMED_OUT) {
                return new CallbackTimeoutException(callback);
            }
        }
        if (callback != null
                && submitter != null
                && isTerminalStatus(submitter.status())
                && submitter.status() != OperationStatus.SUCCEEDED) {
            var error = submitter.stepDetails().error();
            var cause = StepInterruptedException.isStepInterruptedException(error)
                    ? new StepInterruptedException(submitter)
                    : new StepFailedException(submitter);
            return new CallbackSubmitterException(callback, cause);
        }
        return new IllegalStateException("Unknown waitForCallback status");
    }

    private static Operation findChild(ExtensionContextFailure failure, OperationType type) {
        return failure.childOperations().stream()
                .filter(summary -> summary.operationType() == type)
                .map(summary -> summary.operation())
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
