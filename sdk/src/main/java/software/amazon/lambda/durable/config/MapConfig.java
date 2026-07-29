// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.config;

import java.util.Objects;
import java.util.function.BiFunction;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Configuration for map operations.
 *
 * <p>Defaults to lenient completion (all items run regardless of failures) and unlimited concurrency.
 */
public class MapConfig {
    private final Integer maxConcurrency;
    private final CompletionConfig completionConfig;
    private final SerDes serDes;
    private final NestingType nestingType;
    private final BiFunction<Object, Integer, String> itemNamer;

    private MapConfig(Builder builder) {
        this.maxConcurrency = Objects.requireNonNullElse(builder.maxConcurrency, Integer.MAX_VALUE);
        this.completionConfig = Objects.requireNonNullElse(builder.completionConfig, CompletionConfig.allCompleted());
        this.nestingType = Objects.requireNonNullElse(builder.nestingType, NestingType.NESTED);
        this.serDes = builder.serDes;
        this.itemNamer = builder.itemNamer;
    }

    /** @return max concurrent items, or null for unlimited */
    public Integer maxConcurrency() {
        return maxConcurrency;
    }

    /** @return completion criteria, defaults to {@link CompletionConfig#allCompleted()} */
    public CompletionConfig completionConfig() {
        return completionConfig;
    }

    /** @return the custom serializer, or null to use the default */
    public SerDes serDes() {
        return serDes;
    }

    /** @return nesting type, defaults to {@link NestingType#NESTED} */
    public NestingType nestingType() {
        return nestingType;
    }

    /**
     * Returns the item namer function, which generates custom names for each map iteration.
     *
     * <p>When provided, the namer is called for each item with the item value and its index.
     * The returned string is used as the operation name for that iteration, replacing the
     * default "map-item-N" naming. The item parameter is typed as {@code Object} and will
     * receive the map item at runtime.
     *
     * @return the item namer function, or null if not set
     */
    public BiFunction<Object, Integer, String> itemNamer() {
        return itemNamer;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .maxConcurrency(maxConcurrency)
                .completionConfig(completionConfig)
                .serDes(serDes)
                .nestingType(nestingType)
                .itemNamer(itemNamer);
    }

    /** Builder for creating MapConfig instances. */
    public static class Builder {
        private NestingType nestingType;
        private Integer maxConcurrency;
        private CompletionConfig completionConfig;
        private SerDes serDes;
        private BiFunction<Object, Integer, String> itemNamer;

        private Builder() {}

        public Builder maxConcurrency(Integer maxConcurrency) {
            if (maxConcurrency != null && maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be at least 1, got: " + maxConcurrency);
            }
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Sets the completion criteria for the map operation.
         *
         * @param completionConfig the completion configuration (default: {@link CompletionConfig#allCompleted()})
         * @return this builder for method chaining
         */
        public Builder completionConfig(CompletionConfig completionConfig) {
            this.completionConfig = completionConfig;
            return this;
        }

        /**
         * Sets the custom serializer to use for serializing map items and results.
         *
         * @param serDes the serializer to use
         * @return this builder for method chaining
         */
        public Builder serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        /**
         * Sets the nesting type for the map operation.
         *
         * @param nestingType the nesting type (default: {@link NestingType#NESTED})
         * @return this builder for method chaining
         */
        public Builder nestingType(NestingType nestingType) {
            this.nestingType = nestingType;
            return this;
        }

        /**
         * Sets the item namer function for generating custom iteration names.
         *
         * <p>The namer receives the item (as {@code Object}) and its index, and returns a string to use as the
         * operation name for that iteration. If null, the default "map-item-N" naming is used.
         *
         * @param itemNamer the item namer function, or null to use default naming
         * @return this builder for method chaining
         */
        public Builder itemNamer(BiFunction<Object, Integer, String> itemNamer) {
            this.itemNamer = itemNamer;
            return this;
        }

        public MapConfig build() {
            return new MapConfig(this);
        }
    }
}