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
        if (itemNamer != null && nestingType == NestingType.FLAT) {
            throw new IllegalArgumentException("itemNamer is not supported with FLAT map nesting");
        }
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
     * Returns the function used to name map iterations.
     *
     * <p>The function receives the item and its zero-based index. A non-null result must satisfy the normal operation
     * name constraints. A null result is preserved as an unnamed iteration.
     *
     * @return the item namer, or null when default iteration naming is used
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
        public NestingType nestingType;
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
         * Sets a function that names each nested map iteration.
         *
         * <p>The function receives the item and its zero-based index. Returning null creates an unnamed iteration.
         * Non-null names are validated before the map allocates an operation ID or emits a checkpoint. Item naming is
         * not supported with {@link NestingType#FLAT} because flat iterations do not have context operations.
         *
         * @param itemNamer the item namer, or null to use default iteration naming
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
