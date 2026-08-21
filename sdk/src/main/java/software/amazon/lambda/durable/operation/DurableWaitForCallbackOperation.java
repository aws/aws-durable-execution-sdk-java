// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.execution.ExecutionManager.isTerminalStatus;
import static software.amazon.lambda.durable.operation.DurableStepOperation.step;

import java.util.Objects;
import java.util.function.BiConsumer;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
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
import software.amazon.lambda.durable.model.SafeCloseable;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Context-free static facade and canonical implementation of durable wait-for-callback operations. */
public final class DurableWaitForCallbackOperation {
    private static final String CALLBACK_SUFFIX = "-callback";
    private static final String SUBMITTER_SUFFIX = "-submitter";
    private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;
    private static final int MAX_NAME_LENGTH = ParameterValidator.MAX_OPERATION_NAME_LENGTH
            - Math.max(CALLBACK_SUFFIX.length(), SUBMITTER_SUFFIX.length());

    private DurableWaitForCallbackOperation() {}

    public static <T> T waitForCallback(String name, Class<T> resultType, Runnable submitter) {
        return waitForCallbackAsync(name, resultType, submitter).get();
    }

    public static <T> T waitForCallback(String name, TypeToken<T> resultType, Runnable submitter) {
        return waitForCallbackAsync(name, resultType, submitter).get();
    }

    public static <T> T waitForCallback(
            String name, Class<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return waitForCallbackAsync(name, resultType, submitter, config).get();
    }

    public static <T> T waitForCallback(
            String name, TypeToken<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return waitForCallbackAsync(name, resultType, submitter, config).get();
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(String name, Class<T> resultType, Runnable submitter) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), submitter);
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(String name, TypeToken<T> resultType, Runnable submitter) {
        return waitForCallbackAsync(
                name, resultType, submitter, WaitForCallbackConfig.builder().build());
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(
            String name, Class<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return waitForCallbackAsync(name, TypeToken.get(resultType), submitter, config);
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(
            String name, TypeToken<T> resultType, Runnable submitter, WaitForCallbackConfig config) {
        return waitForCallbackAsync(ExtensionContext.getCurrentContext(), name, resultType, adapt(submitter), config);
    }

    public static <T> DurableFuture<T> waitForCallbackAsync(
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

    private static BiConsumer<String, StepContext> adapt(Runnable submitter) {
        Objects.requireNonNull(submitter, "submitter cannot be null");
        return (callbackId, ignored) -> {
            try (var scope = WaitForCallbackContext.attach(callbackId)) {
                submitter.run();
            }
        };
    }

    private static <T> ExtensionContextResult<T> executeInChildContext(
            String name,
            TypeToken<T> resultType,
            BiConsumer<String, StepContext> submitter,
            WaitForCallbackConfig config) {
        var child = ExtensionContext.getCurrentContext();
        var callback = child.reserve(name + CALLBACK_SUFFIX)
                .createCallback(
                        OperationSubType.CALLBACK.getValue(),
                        resultType,
                        DurableCallbackOperation.extensionConfig(config.callbackConfig()));
        step(
                name + SUBMITTER_SUFFIX,
                Void.class,
                () -> {
                    submitter.accept(callback.callbackId(), StepContext.requireCurrentContext());
                    return null;
                },
                config.stepConfig());
        return ExtensionContextResult.replayChildrenAboveSize(callback.get(), null, LARGE_RESULT_THRESHOLD);
    }

    private static ExtensionContextConfig extensionConfig(WaitForCallbackConfig config) {
        return ExtensionContextConfig.builder()
                .serDes(config.stepConfig().serDes())
                .errorHandler(DurableWaitForCallbackOperation::translateFailure)
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

    /** Metadata for the callback submitter active on the current SDK-managed thread. */
    public static final class WaitForCallbackContext {
        private static final ThreadLocal<WaitForCallbackContext> CURRENT = new ThreadLocal<>();

        private final String callbackId;

        private WaitForCallbackContext(String callbackId) {
            this.callbackId = Objects.requireNonNull(callbackId, "callbackId cannot be null");
        }

        /** Returns the callback context attached to the current SDK-managed thread. */
        public static WaitForCallbackContext getCurrentContext() {
            var context = CURRENT.get();
            if (context == null) {
                throw new IllegalStateException("WaitForCallbackContext is not active on the current thread");
            }
            return context;
        }

        /** Returns the callback ID to send to the external system. */
        public String getCallbackId() {
            return callbackId;
        }

        /** Attaches callback metadata for the duration of the returned scope. */
        public static SafeCloseable attach(String callbackId) {
            var previous = CURRENT.get();
            CURRENT.set(new WaitForCallbackContext(callbackId));
            return () -> restore(previous);
        }

        private static void restore(WaitForCallbackContext previous) {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /** Configuration for durable wait-for-callback operations. */
    public static final class WaitForCallbackConfig {
        private final DurableStepOperation.StepConfig stepConfig;
        private final DurableCallbackOperation.CallbackConfig callbackConfig;

        private WaitForCallbackConfig(Builder builder) {
            stepConfig =
                    Objects.requireNonNullElseGet(builder.stepConfig, () -> DurableStepOperation.StepConfig.builder()
                            .build());
            callbackConfig = Objects.requireNonNullElseGet(
                    builder.callbackConfig,
                    () -> DurableCallbackOperation.CallbackConfig.builder().build());
        }

        public DurableStepOperation.StepConfig stepConfig() {
            return stepConfig;
        }

        public DurableCallbackOperation.CallbackConfig callbackConfig() {
            return callbackConfig;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder().stepConfig(stepConfig).callbackConfig(callbackConfig);
        }

        /** Builder for {@link WaitForCallbackConfig}. */
        public static final class Builder {
            private DurableStepOperation.StepConfig stepConfig;
            private DurableCallbackOperation.CallbackConfig callbackConfig;

            private Builder() {}

            public Builder stepConfig(DurableStepOperation.StepConfig stepConfig) {
                this.stepConfig = stepConfig;
                return this;
            }

            public Builder callbackConfig(DurableCallbackOperation.CallbackConfig callbackConfig) {
                this.callbackConfig = callbackConfig;
                return this;
            }

            public WaitForCallbackConfig build() {
                return new WaitForCallbackConfig(this);
            }
        }
    }
}
