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
     * <p>When provided, the namer is called for each item with the item value and its index. The returned string is
     * used as the operation name for that iteration, replacing the default {@code "<mapName>-iteration-N"} naming. The
     * item parameter is typed as {@code Object} and will receive the map item at runtime.
     *
     * <p>The returned name must satisfy the same constraints as any other operation name: non-empty, within the maximum
     * operation-name length, and printable ASCII only. An invalid name fails fast with {@link IllegalArgumentException}
     * when the iteration is enqueued, rather than failing later at checkpoint time. Returning {@code null} is permitted
     * and falls back to the default naming for that iteration.
     *
     * <p>The namer must be deterministic: replay regenerates iteration names and compares them against the checkpointed
     * names, so a namer whose output varies between invocations risks a non-deterministic replay failure.
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
         * operation name for that iteration. If the namer is null, or returns null for an item, the default
         * {@code "<mapName>-iteration-N"} naming is used for that iteration.
         *
         * <p>A non-null name must be non-empty, within the maximum operation-name length, and printable ASCII only;
         * otherwise an {@link IllegalArgumentException} is thrown when the iteration is enqueued. The namer must also
         * be deterministic across replays.
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
