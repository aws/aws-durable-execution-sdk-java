// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.client.LambdaDurableFunctionsClient;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;

/**
 * Configuration for DurableHandler initialization. This class provides a builder pattern for configuring SDK components
 * including DurableExecutionClient, SerDes, and ExecutorService.
 *
 * <p>Configuration is initialized once during Lambda cold start and remains immutable throughout the execution
 * lifecycle.
 *
 * <p>Example usage with default settings:
 *
 * <pre>{@code
 * @Override
 * protected DurableConfig createConfiguration() {
 *     return DurableConfig.builder()
 *         .withDurableExecutionClient(customClient)
 *         .withSerDes(customSerDes)
 *         .build();
 * }
 * }</pre>
 *
 * <p>Example usage with custom Lambda client configuration using {@link LambdaDurableFunctionsClient}:
 *
 * <pre>{@code
 * @Override
 * protected DurableConfig createConfiguration() {
 *     // Create a custom Lambda client with specific configuration
 *     LambdaClient lambdaClient = LambdaClient.builder()
 *         .region(Region.US_WEST_2)
 *         .credentialsProvider(ProfileCredentialsProvider.create("my-profile"))
 *         .httpClient(ApacheHttpClient.builder()
 *             .connectionTimeout(Duration.ofSeconds(30))
 *             .socketTimeout(Duration.ofSeconds(60))
 *             .build())
 *         .overrideConfiguration(ClientOverrideConfiguration.builder()
 *             .retryPolicy(RetryPolicy.builder()
 *                 .numRetries(5)
 *                 .build())
 *             .build())
 *         .build();
 *
 *     // Wrap the Lambda client with LambdaDurableFunctionsClient
 *     DurableExecutionClient durableClient = new LambdaDurableFunctionsClient(lambdaClient);
 *
 *     // Configure DurableConfig with the custom client
 *     return DurableConfig.builder()
 *         .withDurableExecutionClient(durableClient)
 *         .build();
 * }
 * }</pre>
 */
public final class DurableConfig {
    private static final Logger logger = LoggerFactory.getLogger(DurableConfig.class);

    private final DurableExecutionClient durableExecutionClient;
    private final SerDes serDes;
    private final ExecutorService executorService;

    private DurableConfig(Builder builder) {
        this.durableExecutionClient = builder.durableExecutionClient != null
                ? builder.durableExecutionClient
                : createDefaultDurableExecutionClient();
        this.serDes = builder.serDes != null ? builder.serDes : new JacksonSerDes();
        this.executorService = builder.executorService != null ? builder.executorService : createDefaultExecutor();
    }

    /**
     * Creates a DurableConfig with default settings. Uses default DurableExecutionClient and JacksonSerDes.
     *
     * @return DurableConfig with default configuration
     */
    public static DurableConfig defaultConfig() {
        return new Builder().build();
    }

    /**
     * Creates a new builder for DurableConfig.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Gets the configured DurableExecutionClient.
     *
     * @return DurableExecutionClient instance
     */
    public DurableExecutionClient getDurableExecutionClient() {
        return durableExecutionClient;
    }

    /**
     * Gets the configured SerDes.
     *
     * @return SerDes instance
     */
    public SerDes getSerDes() {
        return serDes;
    }

    /**
     * Gets the configured ExecutorService.
     *
     * @return ExecutorService instance (never null)
     */
    public ExecutorService getExecutorService() {
        return executorService;
    }

    /**
     * Creates a default DurableExecutionClient with production LambdaClient. Uses
     * EnvironmentVariableCredentialsProvider and region from AWS_REGION.
     *
     * @return Default DurableExecutionClient instance
     */
    private static DurableExecutionClient createDefaultDurableExecutionClient() {
        logger.debug("Creating default DurableExecutionClient");
        var region = System.getenv(SdkSystemSetting.AWS_REGION.environmentVariable());
        if (region == null || region.isEmpty()) {
            throw new IllegalStateException(
                    "Failed to create default DurableExecutionClient: Missing AWS region configuration. "
                            + "Set AWS_REGION environment variable or provide custom DurableExecutionClient.");
        }

        var lambdaClient = LambdaClient.builder()
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .region(Region.of(region))
                .build();

        logger.debug("Default DurableExecutionClient created for region: {}", region);
        return new LambdaDurableFunctionsClient(lambdaClient);
    }

    /**
     * Creates a default ExecutorService for durable execution. Uses a cached thread pool with daemon threads.
     *
     * @return Default ExecutorService instance
     */
    private static ExecutorService createDefaultExecutor() {
        logger.debug("Creating default ExecutorService");
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setName("durable-exec-" + t.getId());
            t.setDaemon(true);
            return t;
        });
    }

    /** Builder for DurableConfig. Provides fluent API for configuring SDK components. */
    public static final class Builder {
        private DurableExecutionClient durableExecutionClient;
        private SerDes serDes;
        private ExecutorService executorService;

        private Builder() {}

        /**
         * Sets a custom DurableExecutionClient. This is useful for testing with mock clients or using custom backend
         * implementations.
         *
         * @param durableExecutionClient Custom DurableExecutionClient instance
         * @return This builder
         * @throws NullPointerException if durableExecutionClient is null
         */
        public Builder withDurableExecutionClient(DurableExecutionClient durableExecutionClient) {
            this.durableExecutionClient =
                    Objects.requireNonNull(durableExecutionClient, "DurableExecutionClient cannot be null");
            return this;
        }

        /**
         * Sets a custom SerDes implementation.
         *
         * @param serDes Custom SerDes instance
         * @return This builder
         * @throws NullPointerException if serDes is null
         */
        public Builder withSerDes(SerDes serDes) {
            this.serDes = Objects.requireNonNull(serDes, "SerDes cannot be null");
            return this;
        }

        /**
         * Sets a custom ExecutorService. If not set, a default cached thread pool will be created.
         *
         * @param executorService Custom ExecutorService instance
         * @return This builder
         */
        public Builder withExecutorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * Builds the DurableConfig instance.
         *
         * @return Immutable DurableConfig instance
         */
        public DurableConfig build() {
            return new DurableConfig(this);
        }
    }
}
