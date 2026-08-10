// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import java.util.Objects;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.extension.ExtensionContext;
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
