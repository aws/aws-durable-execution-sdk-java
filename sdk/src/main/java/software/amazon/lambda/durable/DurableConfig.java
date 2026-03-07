// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.LambdaClientBuilder;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionStateRequest;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.client.LambdaDurableFunctionsClient;
import software.amazon.lambda.durable.logging.LoggerConfig;
import software.amazon.lambda.durable.retry.PollingStrategies;
import software.amazon.lambda.durable.retry.PollingStrategy;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

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

    private static final String VERSION_FILE = "/version.prop";
    private static final String PROJECT_VERSION = getProjectVersion(VERSION_FILE);
    private static final String USER_AGENT_SUFFIX = "@aws/durable-execution-sdk-java/" + PROJECT_VERSION;

    private final DurableExecutionClient durableExecutionClient;
    private final SerDes serDes;
    private final ExecutorService executorService;
    private final LoggerConfig loggerConfig;
    private final PollingStrategy pollingStrategy;
    private final Duration checkpointDelay;

    private DurableConfig(Builder builder) {
        this.durableExecutionClient = builder.durableExecutionClient != null
                ? builder.durableExecutionClient
                : createDefaultDurableExecutionClient();
        this.serDes = builder.serDes != null ? builder.serDes : new JacksonSerDes();
        this.executorService = builder.executorService != null ? builder.executorService : createDefaultExecutor();
        this.loggerConfig = builder.loggerConfig != null ? builder.loggerConfig : LoggerConfig.defaults();
        this.pollingStrategy =
                builder.pollingStrategy != null ? builder.pollingStrategy : PollingStrategies.Presets.DEFAULT;
        this.checkpointDelay = builder.checkpointDelay != null ? builder.checkpointDelay : Duration.ofMillis(1);
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
     * Gets the polling strategy.
     *
     * @return PollingStrategy instance (never null)
     */
    public PollingStrategy getPollingStrategy() {
        return pollingStrategy;
    }

    /**
     * Gets the configured checkpoint delay used by the internal request batcher.
     *
     * <p>The checkpoint delay controls how long the SDK waits before flushing a batch of checkpoint requests to the
     * backend. A longer delay allows more operations to accumulate in a single batch (up to 100 items or 750KB),
     * reducing the total number of API calls at the cost of added latency before results are persisted. A shorter delay
     * flushes sooner, lowering checkpoint latency but potentially sending more, smaller batches.
     *
     * <p>Defaults to 1 millisecond.
     *
     * @return checkpoint delay as a Duration
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

        var lambdaClient = addUserAgentSuffix(LambdaClient.builder()
                        .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                        .region(Region.of(region)))
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

    static LambdaClientBuilder addUserAgentSuffix(LambdaClientBuilder builder) {
        return builder.overrideConfiguration(builder.overrideConfiguration().toBuilder()
                .putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_SUFFIX, USER_AGENT_SUFFIX)
                .build());
    }

    private static String getProjectVersion(String versionFile) {
        InputStream stream = DurableConfig.class.getResourceAsStream(versionFile);
        if (stream == null) {
            return "UNKNOWN";
        }
        Properties props = new Properties();
        try {
            props.load(stream);
            stream.close();
            return (String) props.get("version");
        } catch (IOException e) {
            return "UNKNOWN";
        }
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
        private PollingStrategy pollingStrategy;
        private Duration checkpointDelay;

        private Builder() {}

        /**
         * Sets a custom LambdaClient for production use. Use this method to customize the AWS SDK client with specific
         * regions, credentials, timeouts, or retry policies.
         *
         * <p>Example:
         *
         * <pre>{@code
         * LambdaClientBuilder lambdaClientBuilder = LambdaClient.builder()
         *     .region(Region.US_WEST_2)
         *     .credentialsProvider(ProfileCredentialsProvider.create("my-profile"));
         *
         * DurableConfig.builder()
         *     .withLambdaClientBuilder(lambdaClientBuilder)
         *     .build();
         * }</pre>
         *
         * @param lambdaClientBuilder Custom LambdaClientBuilder instance
         * @return This builder
         * @throws NullPointerException if lambdaClient is null
         */
        public Builder withLambdaClientBuilder(LambdaClientBuilder lambdaClientBuilder) {
            Objects.requireNonNull(lambdaClientBuilder, "LambdaClient cannot be null");
            this.durableExecutionClient = new LambdaDurableFunctionsClient(
                    addUserAgentSuffix(lambdaClientBuilder).build());
            return this;
        }

        /**
         * Sets a custom DurableExecutionClient.
         *
         * <p><b>Note:</b> This method is primarily intended for testing with mock clients (e.g.,
         * {@code LocalMemoryExecutionClient}). For production use with a custom AWS SDK client, prefer
         * {@link #withLambdaClientBuilder(LambdaClientBuilder)}.
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
         * Sets the polling strategy. If not set, defaults to 1 second with full jitter and 2x backoff.
         *
         * @param pollingStrategy Custom PollingStrategy instance
         * @return This builder
         */
        public Builder withPollingStrategy(PollingStrategy pollingStrategy) {
            // No validation - polling intervals can be less than 1 second (e.g., 200ms with backoff)
            this.pollingStrategy = pollingStrategy;
            return this;
        }

        /**
         * Sets the delay before the SDK flushes a batch of checkpoint requests to the backend.
         *
         * <p>The checkpoint delay controls the batching window for checkpoint API calls. When an operation completes,
         * its checkpoint request is queued and the batcher waits up to this duration for additional requests before
         * sending the batch. This allows multiple concurrent operations to be checkpointed in a single API call (up to
         * 100 items or 750KB per batch).
         *
         * <p>Tradeoffs:
         *
         * <ul>
         *   <li>Shorter delay (e.g., {@code Duration.ZERO}) — checkpoints are flushed almost immediately, minimizing
         *       the window of un-persisted work but producing more API calls.
         *   <li>Longer delay (e.g., {@code Duration.ofMillis(10)}) — more operations can be grouped into each batch,
         *       reducing API call count and improving throughput for workflows with many concurrent steps.
         * </ul>
         *
         * <p>Defaults to 1 millisecond if not set.
         *
         * @param duration the maximum time to wait before flushing a checkpoint batch
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
