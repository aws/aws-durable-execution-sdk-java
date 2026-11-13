// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import software.amazon.lambda.durable.util.DistributedMapValidation;

/** Processor configuration for a distributed map run. */
public class DistributedMapProcessor {

    /** Per-batch response mode reported by the processor. */
    public enum ResponseMode {
        BATCH,
        ITEM_OUTCOMES
    }

    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 10000;
    private static final int MAX_NAME_PREFIX_LENGTH = 36;

    private final String functionName;
    private final ResponseMode responseMode;
    private final Integer batchSize;
    private final DistributedMapProcessorRetryConfig retry;
    private final String durableExecutionNamePrefix;

    private DistributedMapProcessor(Builder builder) {
        DistributedMapValidation.validateFunctionName(builder.functionName);
        if (builder.batchSize != null && (builder.batchSize < MIN_BATCH_SIZE || builder.batchSize > MAX_BATCH_SIZE)) {
            throw new IllegalArgumentException("batchSize must be between 1 and 10000, got: " + builder.batchSize);
        }
        if (builder.durableExecutionNamePrefix != null
                && (builder.durableExecutionNamePrefix.isEmpty()
                        || builder.durableExecutionNamePrefix.length() > MAX_NAME_PREFIX_LENGTH)) {
            throw new IllegalArgumentException("durableExecutionNamePrefix must be between 1 and 36 characters, got: "
                    + builder.durableExecutionNamePrefix.length());
        }
        this.functionName = builder.functionName;
        this.responseMode = builder.responseMode;
        this.batchSize = builder.batchSize;
        this.retry = builder.retry;
        this.durableExecutionNamePrefix = builder.durableExecutionNamePrefix;
    }

    public String functionName() {
        return functionName;
    }

    public ResponseMode responseMode() {
        return responseMode;
    }

    public Integer batchSize() {
        return batchSize;
    }

    /** Returns the retry settings, or null for the service defaults. */
    public DistributedMapProcessorRetryConfig retry() {
        return retry;
    }

    public String durableExecutionNamePrefix() {
        return durableExecutionNamePrefix;
    }

    /** Processor that reports a single pass/fail outcome for the whole batch, with no per-item results. */
    public static Builder reportBatchOutcome(String functionName) {
        return new Builder(functionName, ResponseMode.BATCH);
    }

    /**
     * Processor that may report a failure or an output for each item. Every item it does not list succeeds with no
     * output.
     */
    public static Builder reportItemOutcomes(String functionName) {
        return new Builder(functionName, ResponseMode.ITEM_OUTCOMES);
    }

    /** Builder for DistributedMapProcessor. */
    public static class Builder {
        private final String functionName;
        private final ResponseMode responseMode;
        private Integer batchSize;
        private DistributedMapProcessorRetryConfig retry;
        private String durableExecutionNamePrefix;

        private Builder(String functionName, ResponseMode responseMode) {
            this.functionName = functionName;
            this.responseMode = responseMode;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        /**
         * Sets the per-item retry policy. Omit it to take the service defaults of 2 attempts within 6 hours.
         *
         * @param retry the retry settings
         * @return this builder
         */
        public Builder retry(DistributedMapProcessorRetryConfig retry) {
            this.retry = retry;
            return this;
        }

        public Builder durableExecutionNamePrefix(String durableExecutionNamePrefix) {
            this.durableExecutionNamePrefix = durableExecutionNamePrefix;
            return this;
        }

        public DistributedMapProcessor build() {
            return new DistributedMapProcessor(this);
        }
    }
}
