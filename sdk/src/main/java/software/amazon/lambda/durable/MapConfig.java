// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

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

    private MapConfig(Builder builder) {
        this.maxConcurrency = builder.maxConcurrency;
        this.completionConfig = builder.completionConfig;
        this.serDes = builder.serDes;
    }

    /** @return max concurrent items, or null for unlimited */
    public Integer maxConcurrency() {
        return maxConcurrency;
    }

    /** @return completion criteria, defaults to {@link CompletionConfig#allCompleted()} */
    public CompletionConfig completionConfig() {
        return completionConfig != null ? completionConfig : CompletionConfig.allCompleted();
    }

    /** @return the custom serializer, or null to use the default */
    public SerDes serDes() {
        return serDes;
    }

    public static Builder builder() {
        return new Builder(null, null, null);
    }

    public Builder toBuilder() {
        return new Builder(maxConcurrency, completionConfig, serDes);
    }

    /** Builder for creating MapConfig instances. */
    public static class Builder {
        private Integer maxConcurrency;
        private CompletionConfig completionConfig;
        private SerDes serDes;

        private Builder(Integer maxConcurrency, CompletionConfig completionConfig, SerDes serDes) {
            this.maxConcurrency = maxConcurrency;
            this.completionConfig = completionConfig;
            this.serDes = serDes;
        }

        public Builder maxConcurrency(Integer maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder completionConfig(CompletionConfig completionConfig) {
            this.completionConfig = completionConfig;
            return this;
        }

        public Builder serDes(SerDes serDes) {
            this.serDes = serDes;
            return this;
        }

        public MapConfig build() {
            if (maxConcurrency != null && maxConcurrency < 1) {
                throw new IllegalArgumentException("maxConcurrency must be at least 1, got: " + maxConcurrency);
            }
            return new MapConfig(this);
        }
    }
}
