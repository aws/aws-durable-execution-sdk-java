// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dag;

import java.util.Optional;
import software.amazon.lambda.durable.annotations.Experimental;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration for a DAG. All fields are optional.
 *
 * <p>Note: there is deliberately no {@code summaryGenerator}. The DAG container checkpoints a single SDK-owned envelope
 * that is readable on its own, so no customer-supplied summary string is ever written into a payload the SDK parses
 * back. Oversize aggregates degrade by dropping the per-task {@code tasks} array (its absence is the offload signal)
 * while the counts, completion reason and in-flight task names always survive.
 *
 * @param maxConcurrency maximum number of top-level tasks running concurrently; must be {@code >= 1} if present. When
 *     unset, the DAG scheduler defaults to {@code 40} (previously unlimited). This bounds the DAG scheduler only — the
 *     top-level tasks of this DAG — and is not inherited by a task's own internal fan-out: a {@code map} or
 *     {@code parallel} task keeps its unlimited default unless configured, and a nested {@code dag} gets its own
 *     independent default of 40. An explicit value always wins, including one above the default.
 * @param completionConfig early-completion policy (default: drain the whole reachable graph)
 * @param defaultTriggerRule default trigger rule (default {@link TriggerRule#ALL_SUCCESS})
 * @param serDes custom serializer/deserializer for the aggregate {@link DagResult}
 * @apiNote <b>Experimental.</b> This API is experimental and may be changed or removed in future releases without a
 *     major-version bump.
 */
@Experimental
public record DagConfig(
        Optional<Integer> maxConcurrency,
        Optional<DagCompletionConfig> completionConfig,
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
                    Optional.ofNullable(defaultTriggerRule),
                    Optional.ofNullable(serDes));
        }
    }
}
