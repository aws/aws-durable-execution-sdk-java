// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.Optional;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.retry.RetryStrategy;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration for a DAG. All fields are optional.
 *
 * <p>Note: unlike the JS spec's {@code DagConfig}, there is no {@code summaryGenerator} — it has no native precedent in
 * the Java SDK, and large-result handling relies on native child-context re-execution rather than a summary envelope.
 *
 * @param maxConcurrency maximum number of top-level tasks running concurrently; must be {@code >= 1} if present
 *     (default: unlimited)
 * @param completionConfig early-completion policy (default: drain the whole reachable graph)
 * @param defaultRetryStrategy default retry strategy applied to tasks that do not specify one
 * @param defaultTriggerRule default trigger rule (default {@link TriggerRule#ALL_SUCCESS})
 * @param serDes custom serializer/deserializer for the aggregate {@link DagResult}
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record DagConfig(
        Optional<Integer> maxConcurrency,
        Optional<DagCompletionConfig> completionConfig,
        Optional<RetryStrategy> defaultRetryStrategy,
        Optional<TriggerRule> defaultTriggerRule,
        Optional<SerDes> serDes) {

    /** Validates invariants. */
    public DagConfig {
        if (maxConcurrency.isPresent() && maxConcurrency.get() < 1) {
            throw new IllegalArgumentException("maxConcurrency must be at least 1, got: " + maxConcurrency.get());
        }
    }

    /** Returns a new builder. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DagConfig}.
     *
     * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
     *     major-version bump.
     */
    @Experimental
    public static final class Builder {
        private Integer maxConcurrency;
        private DagCompletionConfig completionConfig;
        private RetryStrategy defaultRetryStrategy;
        private TriggerRule defaultTriggerRule;
        private SerDes serDes;

        private Builder() {}

        public Builder maxConcurrency(Integer maxConcurrency) {
            if (maxConcurrency != null && maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be at least 1, got: " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder completionConfig(DagCompletionConfig completionConfig) {
            this.completionConfig = completionConfig;
            return this;
        }

        public Builder defaultRetryStrategy(RetryStrategy defaultRetryStrategy) {
            this.defaultRetryStrategy = defaultRetryStrategy;
            return this;
        }

        public Builder defaultTriggerRule(TriggerRule defaultTriggerRule) {
            this.defaultTriggerRule = defaultTriggerRule;
            return this;
        }

        public Builder serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        public DagConfig build() {
            return new DagConfig(
                    Optional.ofNullable(maxConcurrency),
                    Optional.ofNullable(completionConfig),
                    Optional.ofNullable(defaultRetryStrategy),
                    Optional.ofNullable(defaultTriggerRule),
                    Optional.ofNullable(serDes));
        }
    }
}
