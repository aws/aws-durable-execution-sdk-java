// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static software.amazon.lambda.durable.config.NestingType.FLAT;
import static software.amazon.lambda.durable.model.OperationSubType.PARALLEL;
import static software.amazon.lambda.durable.model.OperationSubType.PARALLEL_BRANCH;
import static software.amazon.lambda.durable.operation.OperationConcurrencyCoordinator.ItemStatus.FAILED;
import static software.amazon.lambda.durable.operation.OperationConcurrencyCoordinator.ItemStatus.SKIPPED;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextReplayContext;
import software.amazon.lambda.durable.extension.ExtensionContextResult;
import software.amazon.lambda.durable.model.ParallelResult;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ParameterValidator;

/** Context-free static facade and canonical implementation of durable PARALLEL operations. */
public final class DurableParallelOperation {
    private DurableParallelOperation() {}

    public static ParallelDurableFuture parallel(String name) {
        return parallel(name, ParallelConfig.builder().build());
    }

    public static ParallelDurableFuture parallel(String name, ParallelConfig config) {
        return parallel(ExtensionContext.getCurrentContext(), name, config);
    }

    public static ParallelDurableFuture parallel(ExtensionContext context, String name, ParallelConfig config) {
        Objects.requireNonNull(context, "context cannot be null");
        Objects.requireNonNull(config, "config cannot be null");
        ParameterValidator.validateOperationName(name);
        return new ParallelOperationFuture(context, name, config);
    }

    private static final class ParallelOperationFuture implements ParallelDurableFuture {
        private static final int LARGE_RESULT_THRESHOLD = 256 * 1024;

        private final Object lock = new Object();
        private final ParallelConfig config;
        private final SerDes defaultSerDes;
        private final List<BranchDefinition<?>> branches = new ArrayList<>();
        private final DurableFuture<ParallelResult> parentFuture;
        private ExtensionContext childContext;
        private OperationConcurrencyCoordinator coordinator;
        private ParallelResult replayState;
        private boolean registrationClosed;

        ParallelOperationFuture(ExtensionContext context, String name, ParallelConfig config) {
            this.config = config;
            defaultSerDes = context.getDurableConfig().getSerDes();
            var parent = context.reserve(name);
            parentFuture = parent.runInChildContextAsync(
                    PARALLEL.getValue(),
                    parallelResultType(),
                    this::executeInChildContext,
                    parentConfig(defaultSerDes));
        }

        @Override
        public <T> DurableFuture<T> branch(
                String name,
                TypeToken<T> resultType,
                Function<DurableContext, T> function,
                software.amazon.lambda.durable.config.ParallelBranchConfig config) {
            Objects.requireNonNull(resultType, "resultType cannot be null");
            Objects.requireNonNull(function, "function cannot be null");
            Objects.requireNonNull(config, "config cannot be null");
            ParameterValidator.validateOperationName(name);

            synchronized (lock) {
                ensureRegistrationOpen();
                var definition = new BranchDefinition<>(name, resultType, function, config.toOperationConfig());
                branches.add(definition);
                if (coordinator != null) {
                    registerBranch(definition, branches.size() - 1);
                }
                return definition.future;
            }
        }

        @Override
        public ParallelResult get() {
            closeRegistration();
            return rebuildResult(parentFuture.get());
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return parentFuture.completionFuture();
        }

        @Override
        public void close() {
            if (closeRegistration()) {
                parentFuture.get();
            }
        }

        private ExtensionContextResult<ParallelResult> executeInChildContext() {
            var replay = ExtensionContextReplayContext.<ParallelResult>getCurrentContext();
            initializeCoordinator(ExtensionContext.getCurrentContext(), replay);
            var completion = replayState == null
                    ? coordinator.awaitCompletion()
                    : coordinator.awaitCompletion(expectedCompletion(replayState));
            var result = constructResult(completion);
            return ExtensionContextResult.replayChildren(result, result);
        }

        private void initializeCoordinator(
                ExtensionContext context, ExtensionContextReplayContext<ParallelResult> replayContext) {
            synchronized (lock) {
                childContext = context;
                replayState = replayContext.isReplayingChildren() ? replayContext.getReplayState() : null;
                if (replayContext.isReplayingChildren() && replayState == null) {
                    throw new IllegalStateException("Missing result in completed Parallel operation");
                }
                coordinator = new OperationConcurrencyCoordinator(config.maxConcurrency(), config.completionConfig());
                for (int index = 0; index < branches.size(); index++) {
                    registerBranch(branches.get(index), index);
                }
                if (registrationClosed) {
                    coordinator.closeRegistration();
                }
            }
        }

        private <T> void registerBranch(BranchDefinition<T> definition, int index) {
            var reservation = childContext.reserve(definition.name);
            var skipped = shouldSkip(index);
            var item = coordinator.register(
                    () -> reservation.runInChildContextAsync(
                            PARALLEL_BRANCH.getValue(),
                            definition.resultType,
                            () -> ExtensionContextResult.replayChildrenAboveSize(
                                    definition.function.apply(DurableContext.getCurrentContext()),
                                    null,
                                    LARGE_RESULT_THRESHOLD),
                            branchConfig(definition.config)),
                    skipped);
            definition.future.bind(item.future());
        }

        private boolean shouldSkip(int index) {
            return replayState != null
                    && (replayState.statuses().size() <= index
                            || replayState.statuses().get(index) == ParallelResult.Status.SKIPPED);
        }

        private boolean closeRegistration() {
            synchronized (lock) {
                if (registrationClosed) {
                    return false;
                }
                registrationClosed = true;
                if (coordinator != null) {
                    coordinator.closeRegistration();
                }
                return true;
            }
        }

        private void ensureRegistrationOpen() {
            if (registrationClosed) {
                throw new IllegalStateException("Cannot add branches after join() has been called");
            }
        }

        private ParallelResult rebuildResult(ParallelResult result) {
            synchronized (lock) {
                if (result == null) {
                    return null;
                }
                var statuses = new ArrayList<>(result.statuses());
                while (statuses.size() < branches.size()) {
                    statuses.add(ParallelResult.Status.SKIPPED);
                }
                var succeeded = Math.toIntExact(statuses.stream()
                        .filter(status -> status == ParallelResult.Status.SUCCEEDED)
                        .count());
                var failed = Math.toIntExact(statuses.stream()
                        .filter(status -> status == ParallelResult.Status.FAILED)
                        .count());
                return new ParallelResult(
                        statuses.size(),
                        succeeded,
                        failed,
                        statuses.size() - succeeded - failed,
                        result.completionStatus(),
                        List.copyOf(statuses));
            }
        }

        private static ParallelResult constructResult(OperationConcurrencyCoordinator.Completion completion) {
            var statuses = completion.items().stream()
                    .map(item -> item.status() == FAILED
                            ? ParallelResult.Status.FAILED
                            : item.status() == SKIPPED
                                    ? ParallelResult.Status.SKIPPED
                                    : ParallelResult.Status.SUCCEEDED)
                    .toList();
            var succeeded = Math.toIntExact(statuses.stream()
                    .filter(status -> status == ParallelResult.Status.SUCCEEDED)
                    .count());
            var failed = Math.toIntExact(statuses.stream()
                    .filter(status -> status == ParallelResult.Status.FAILED)
                    .count());
            return new ParallelResult(
                    statuses.size(),
                    succeeded,
                    failed,
                    statuses.size() - succeeded - failed,
                    completion.completionDecision().completionStatus(),
                    statuses);
        }

        private ExtensionContextConfig branchConfig(ParallelBranchConfig branchConfig) {
            return ExtensionContextConfig.builder()
                    .serDes(branchConfig.serDes() == null ? defaultSerDes : branchConfig.serDes())
                    .isVirtual(config.nestingType() == FLAT)
                    .build();
        }

        private static OperationConcurrencyCoordinator.ExpectedCompletionStatus expectedCompletion(
                ParallelResult replayState) {
            return new OperationConcurrencyCoordinator.ExpectedCompletionStatus(
                    replayState.succeeded() + replayState.failed(),
                    CompletionConfig.CompletionDecision.complete(replayState.completionStatus()));
        }

        private static ExtensionContextConfig parentConfig(SerDes serDes) {
            return ExtensionContextConfig.builder()
                    .serDes(serDes)
                    .emitUserFunctionEvents(false)
                    .suppressLateChildCheckpoints(true)
                    .build();
        }

        private static TypeToken<ParallelResult> parallelResultType() {
            return TypeToken.get(ParallelResult.class);
        }

        private static final class BranchDefinition<T> {
            private final String name;
            private final TypeToken<T> resultType;
            private final Function<DurableContext, T> function;
            private final ParallelBranchConfig config;
            private final DeferredDurableFuture<T> future = new DeferredDurableFuture<>();

            private BranchDefinition(
                    String name,
                    TypeToken<T> resultType,
                    Function<DurableContext, T> function,
                    ParallelBranchConfig config) {
                this.name = name;
                this.resultType = resultType;
                this.function = function;
                this.config = config;
            }
        }
    }

    /** Configuration for durable PARALLEL operations. */
    public static final class ParallelConfig {
        private final int maxConcurrency;
        private final CompletionConfig completionConfig;
        private final NestingType nestingType;

        private ParallelConfig(Builder builder) {
            maxConcurrency = Objects.requireNonNullElse(builder.maxConcurrency, Integer.MAX_VALUE);
            completionConfig = Objects.requireNonNullElseGet(builder.completionConfig, CompletionConfig::allCompleted);
            nestingType = Objects.requireNonNullElse(builder.nestingType, NestingType.NESTED);
        }

        public int maxConcurrency() {
            return maxConcurrency;
        }

        public CompletionConfig completionConfig() {
            return completionConfig;
        }

        public NestingType nestingType() {
            return nestingType;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder()
                    .maxConcurrency(maxConcurrency)
                    .completionConfig(completionConfig)
                    .nestingType(nestingType);
        }

        /** Builder for {@link ParallelConfig}. */
        public static final class Builder {
            private Integer maxConcurrency;
            private CompletionConfig completionConfig;
            private NestingType nestingType;

            private Builder() {}

            public Builder maxConcurrency(Integer maxConcurrency) {
                if (maxConcurrency != null && maxConcurrency < 1) {
                    throw new IllegalArgumentException("maxConcurrency must be at least 1, got: " + maxConcurrency);
                }
                this.maxConcurrency = maxConcurrency;
                return this;
            }

            public Builder completionConfig(CompletionConfig completionConfig) {
                if (completionConfig != null
                        && !completionConfig.hasCustomShouldComplete()
                        && completionConfig.toleratedFailurePercentage() != null) {
                    throw new IllegalArgumentException("ParallelConfig does not support toleratedFailurePercentage");
                }
                this.completionConfig = completionConfig;
                return this;
            }

            public Builder nestingType(NestingType nestingType) {
                this.nestingType = nestingType;
                return this;
            }

            public ParallelConfig build() {
                return new ParallelConfig(this);
            }
        }
    }

    /** Configuration for a durable PARALLEL branch. */
    public static final class ParallelBranchConfig {
        private final SerDes serDes;

        private ParallelBranchConfig(Builder builder) {
            serDes = builder.serDes;
        }

        public SerDes serDes() {
            return serDes;
        }

        public static Builder builder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder().serDes(serDes);
        }

        /** Builder for {@link ParallelBranchConfig}. */
        public static final class Builder {
            private SerDes serDes;

            private Builder() {}

            public Builder serDes(SerDes serDes) {
                this.serDes = serDes;
                return this;
            }

            public ParallelBranchConfig build() {
                return new ParallelBranchConfig(this);
            }
        }
    }
}
