// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.client.LambdaDurableFunctionsClient;
import com.amazonaws.lambda.durable.logging.LoggerConfig;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateRequest;

/**
 * Configuration for DurableHandler initialization. This class provides a builder pattern for configuring SDK components
 * including LambdaClient, SerDes, and ExecutorService.
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
 * <p>Example usage with custom Lambda client:
 *
 * <pre>{@code
 * @Override
 * protected DurableConfig createConfiguration() {
 *     LambdaClient lambdaClient = LambdaClient.builder()
 *         .region(Region.US_WEST_2)
 *         .credentialsProvider(ProfileCredentialsProvider.create("my-profile"))
 *         .build();
 *
 *     return DurableConfig.builder()
 *         .withLambdaClient(lambdaClient)
 *         .build();
 * }
 * }</pre>
 */
public final class DurableConfig {
    private static final Logger logger = LoggerFactory.getLogger(DurableConfig.class);

    /**
     * Default AWS region used when AWS_REGION environment variable is not set. This prevents initialization failures in
     * testing environments where AWS credentials may not be configured. In production Lambda environments, AWS_REGION
     * is always set by the Lambda runtime.
     */
    private static final String DEFAULT_REGION = "us-east-1";

    private final DurableExecutionClient durableExecutionClient;
    private final SerDes serDes;
    private final ExecutorService executorService;
    private final LoggerConfig loggerConfig;
    private final Duration pollingInterval;
    private final Duration checkpointDelay;

    private DurableConfig(Builder builder) {
        this.durableExecutionClient = builder.durableExecutionClient != null
                ? builder.durableExecutionClient
                : createDefaultDurableExecutionClient();
        this.serDes = builder.serDes != null ? builder.serDes : new JacksonSerDes();
        this.executorService = builder.executorService != null ? builder.executorService : createDefaultExecutor();
        this.loggerConfig = builder.loggerConfig != null ? builder.loggerConfig : LoggerConfig.defaults();
        this.pollingInterval = builder.pollingInterval != null ? builder.pollingInterval : Duration.ofMillis(1000);
        this.checkpointDelay = builder.checkpointDelay != null ? builder.checkpointDelay : Duration.ofSeconds(0);
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
     * Gets the configured LoggerConfig.
     *
     * @return LoggerConfig instance (never null)
     */
    public LoggerConfig getLoggerConfig() {
        return loggerConfig;
    }

    /**
     * Gets the configured polling interval.
     *
     * @return polling interval in Duration.
     */
    public Duration getPollingInterval() {
        return pollingInterval;
    }

    /**
     * Gets the configured checkpoint delay.
     *
     * @return check point in Duration.
     */
    public Duration getCheckpointDelay() {
        return checkpointDelay;
    }

    /**
     * Creates a default DurableExecutionClient with production LambdaClient. Uses
     * EnvironmentVariableCredentialsProvider and region from AWS_REGION. If AWS_REGION is not set, defaults to
     * us-east-1 to avoid initialization failures in testing environments.
     *
     * @return Default DurableExecutionClient instance
     */
    private static DurableExecutionClient createDefaultDurableExecutionClient() {
        logger.debug("Creating default DurableExecutionClient");
        var region = System.getenv(SdkSystemSetting.AWS_REGION.environmentVariable());
        if (region == null || region.isEmpty()) {
            region = DEFAULT_REGION;
            logger.debug("AWS_REGION not set, defaulting to: {}", region);
        }

        var lambdaClient = LambdaClient.builder()
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .region(Region.of(region))
                .build();

        try {
            // Make a dummy call to prime the SDK client. This leads to faster first call times because the HTTP client
            // is already warmed up when the handler executes. More details, see here:
            // https://github.com/aws/aws-sdk-java-v2/issues/1340
            // https://github.com/aws/aws-sdk-java-v2/issues/3801
            lambdaClient.getDurableExecutionState(GetDurableExecutionStateRequest.builder()
                    .checkpointToken("dummyToken")
                    .durableExecutionArn(String.format(
                            "arn:aws:lambda:%s:123456789012:function:dummy:$LATEST/durable-execution/a0c9cbab-3de6-49ea-8630-0ef3bb4874e4/ed8a29c0-6216-3f4a-ad2e-24e2ad70b2d6",
                            region))
                    .maxItems(0)
                    .build());
        } catch (Exception e) {
            // Ignore exceptions since this is a dummy call to prime the SDK client for faster startup times
        }

        logger.debug("Default DurableExecutionClient created for region: {}", region);
        return new LambdaDurableFunctionsClient(lambdaClient);
    }

    /**
     * Creates a default ExecutorService for running user-defined operations. Uses a cached thread pool with daemon
     * threads by default.
     *
     * <p>This executor is used exclusively for user operations. Internal SDK coordination uses the common ForkJoinPool.
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
        private LoggerConfig loggerConfig;
        private Duration pollingInterval;
        private Duration checkpointDelay;

        private Builder() {}

        /**
         * Sets a custom LambdaClient for production use. Use this method to customize the AWS SDK client with specific
         * regions, credentials, timeouts, or retry policies.
         *
         * <p>Example:
         *
         * <pre>{@code
         * LambdaClient lambdaClient = LambdaClient.builder()
         *     .region(Region.US_WEST_2)
         *     .credentialsProvider(ProfileCredentialsProvider.create("my-profile"))
         *     .build();
         *
         * DurableConfig.builder()
         *     .withLambdaClient(lambdaClient)
         *     .build();
         * }</pre>
         *
         * @param lambdaClient Custom LambdaClient instance
         * @return This builder
         * @throws NullPointerException if lambdaClient is null
         */
        public Builder withLambdaClient(LambdaClient lambdaClient) {
            Objects.requireNonNull(lambdaClient, "LambdaClient cannot be null");
            this.durableExecutionClient = new LambdaDurableFunctionsClient(lambdaClient);
            return this;
        }

        /**
         * Sets a custom DurableExecutionClient.
         *
         * <p><b>Note:</b> This method is primarily intended for testing with mock clients (e.g.,
         * {@code LocalMemoryExecutionClient}). For production use with a custom AWS SDK client, prefer
         * {@link #withLambdaClient(LambdaClient)}.
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
         * Sets a custom ExecutorService for running user-defined operations. If not set, a default cached thread pool
         * will be created.
         *
         * <p>This executor is used exclusively for running user-defined operations. Internal SDK coordination (polling,
         * checkpointing) uses the common ForkJoinPool and is not affected by this setting.
         *
         * @param executorService Custom ExecutorService instance
         * @return This builder
         */
        public Builder withExecutorService(ExecutorService executorService) {
            this.executorService = executorService;
            return this;
        }

        /**
         * Sets a custom LoggerConfig. If not set, defaults to suppressing replay logs.
         *
         * @param loggerConfig Custom LoggerConfig instance
         * @return This builder
         */
        public Builder withLoggerConfig(LoggerConfig loggerConfig) {
            this.loggerConfig = Objects.requireNonNull(loggerConfig, "LoggerConfig cannot be null");
            return this;
        }

        /**
         * Sets how often the SDK polls updates from backend.
         *
         * @param duration the polling interval in Duration
         * @return This builder
         */
        public Builder withPollingInterval(Duration duration) {
            // No validation - polling intervals can be less than 1 second (e.g., 200ms with backoff)
            this.pollingInterval = duration;
            return this;
        }

        /**
         * Sets how often the SDK checkpoints updates to backend. If not set, defaults to 0, which disables checkpoint
         * batching.
         *
         * @param duration the checkpoint delay in Duration
         * @return This builder
         */
        public Builder withCheckpointDelay(Duration duration) {
            this.checkpointDelay = duration;
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
