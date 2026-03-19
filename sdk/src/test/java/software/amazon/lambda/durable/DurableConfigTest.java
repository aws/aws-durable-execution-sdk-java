// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.lambda.durable.client.DurableExecutionClient;
import software.amazon.lambda.durable.client.LambdaDurableFunctionsClient;
import software.amazon.lambda.durable.retry.JitterStrategy;
import software.amazon.lambda.durable.retry.PollingStrategies;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;

class DurableConfigTest {

    private DurableExecutionClient mockClient;
    private SerDes mockSerDes;
    private ExecutorService mockExecutor;

    @BeforeEach
    void setUp() {
        mockClient = mock(DurableExecutionClient.class);
        mockSerDes = mock(SerDes.class);
        mockExecutor = mock(ExecutorService.class);
    }

    @Test
    void testDefaultConfig_CreatesWithDefaults() {
        var config = DurableConfig.defaultConfig();

        assertNotNull(config);
        assertNotNull(config.getDurableExecutionClient());
        assertInstanceOf(LambdaDurableFunctionsClient.class, config.getDurableExecutionClient());
        assertNotNull(config.getSerDes());
        assertInstanceOf(JacksonSerDes.class, config.getSerDes());
        assertNotNull(config.getExecutorService());
        assertInstanceOf(ExecutorService.class, config.getExecutorService());
    }

    @Test
    void testBuilder_WithCustomDurableExecutionClient() {
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient).build();

        assertNotNull(config);
        assertEquals(mockClient, config.getDurableExecutionClient());
        assertNotNull(config.getSerDes());
        assertInstanceOf(JacksonSerDes.class, config.getSerDes());
        assertNotNull(config.getExecutorService());
        assertInstanceOf(ExecutorService.class, config.getExecutorService());
    }

    @Test
    void testBuilder_WithCustomSerDes() {
        var config = DurableConfig.builder().withSerDes(mockSerDes).build();

        assertNotNull(config);
        assertNotNull(config.getDurableExecutionClient());
        assertEquals(mockSerDes, config.getSerDes());
        assertNotNull(config.getExecutorService());
    }

    @Test
    void testBuilder_WithCustomExecutorService() {
        var config = DurableConfig.builder().withExecutorService(mockExecutor).build();

        assertNotNull(config);
        assertEquals(mockExecutor, config.getExecutorService());
        assertNotNull(config.getDurableExecutionClient());
        assertNotNull(config.getSerDes());
    }

    @Test
    void testBuilder_WithAllCustomComponents() {
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withSerDes(mockSerDes)
                .withExecutorService(mockExecutor)
                .build();

        assertNotNull(config);
        assertEquals(mockClient, config.getDurableExecutionClient());
        assertEquals(mockSerDes, config.getSerDes());
        assertEquals(mockExecutor, config.getExecutorService());
    }

    @Test
    void testBuilder_NullDurableExecutionClient_ThrowsException() {
        var builder = DurableConfig.builder();

        var exception = assertThrows(NullPointerException.class, () -> {
            builder.withDurableExecutionClient(null);
        });

        assertEquals("DurableExecutionClient cannot be null", exception.getMessage());
    }

    @Test
    void testBuilder_NullSerDes_ThrowsException() {
        var builder = DurableConfig.builder();

        var exception = assertThrows(NullPointerException.class, () -> {
            builder.withSerDes(null);
        });

        assertEquals("SerDes cannot be null", exception.getMessage());
    }

    @Test
    void testBuilder_FluentAPI() {
        var builder = DurableConfig.builder();

        // Verify fluent API returns builder
        assertSame(builder, builder.withDurableExecutionClient(mockClient));
        assertSame(builder, builder.withSerDes(mockSerDes));
        assertSame(builder, builder.withExecutorService(mockExecutor));
    }

    @Test
    void testBuilder_Immutability() {
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withSerDes(mockSerDes)
                .build();

        // Verify config is immutable (no setters available)
        assertNotNull(config.getDurableExecutionClient());
        assertNotNull(config.getSerDes());

        // Build another config from same builder should create new instance
        var config2 = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withSerDes(mockSerDes)
                .build();

        assertNotSame(config, config2);
    }

    @Test
    void testDefaultDurableExecutionClient_UsesDefaultRegion() {
        // Test that default client creation works with default region fallback
        var config = DurableConfig.defaultConfig();
        var client = config.getDurableExecutionClient();

        assertNotNull(client);
        assertInstanceOf(LambdaDurableFunctionsClient.class, client);
    }

    @Test
    void testDefaultSerDes_IsJacksonSerDes() {
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient).build();

        var serDes = config.getSerDes();
        assertInstanceOf(JacksonSerDes.class, serDes);
    }

    @Test
    void testDefaultExecutorService_IsNotNull() {
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient).build();

        var executor = config.getExecutorService();
        assertNotNull(executor);
        assertFalse(executor.isShutdown());
    }

    @Test
    void testBuilder_MultipleBuilds_CreateIndependentInstances() {
        var builder = DurableConfig.builder().withDurableExecutionClient(mockClient);

        var config1 = builder.build();
        var config2 = builder.build();

        assertNotSame(config1, config2);
        assertEquals(config1.getDurableExecutionClient(), config2.getDurableExecutionClient());

        // ExecutorService should be different instances (each gets its own)
        assertNotSame(config1.getExecutorService(), config2.getExecutorService());
    }

    @Test
    void testBuilder_NullExecutorService_AllowedAndUsesDefault() {
        // ExecutorService can be null - DurableConfig will create default
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withExecutorService(null)
                .build();

        assertNotNull(config.getExecutorService());
    }

    @Test
    void testDefaultConfig_CreatesNewInstancesEachTime() {
        var config1 = DurableConfig.defaultConfig();
        var config2 = DurableConfig.defaultConfig();

        assertNotSame(config1, config2);
        assertNotSame(config1.getExecutorService(), config2.getExecutorService());
    }

    @Test
    void testBuilder_EmptyBuild_UsesAllDefaults() {
        var config = DurableConfig.builder().build();

        assertNotNull(config);
        assertNotNull(config.getDurableExecutionClient());
        assertInstanceOf(LambdaDurableFunctionsClient.class, config.getDurableExecutionClient());
        assertNotNull(config.getSerDes());
        assertInstanceOf(JacksonSerDes.class, config.getSerDes());
        assertNotNull(config.getExecutorService());
    }

    @Test
    void testBuilder_PartialConfiguration_FillsDefaults() {
        var config = DurableConfig.builder().withSerDes(mockSerDes).build();

        assertNotNull(config);
        // Custom SerDes
        assertEquals(mockSerDes, config.getSerDes());
        // Default client and executor
        assertNotNull(config.getDurableExecutionClient());
        assertInstanceOf(LambdaDurableFunctionsClient.class, config.getDurableExecutionClient());
        assertNotNull(config.getExecutorService());
    }

    @Test
    void testBuilder_WithLambdaClientBuilder_CreatesLambdaDurableFunctionsClient() {
        var lambdaClientBuilder = LambdaClient.builder()
                .region(software.amazon.awssdk.regions.Region.US_WEST_2)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                        software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create("test", "test")));

        var config = DurableConfig.builder()
                .withLambdaClientBuilder(lambdaClientBuilder)
                .build();

        assertNotNull(config.getDurableExecutionClient());
        assertInstanceOf(LambdaDurableFunctionsClient.class, config.getDurableExecutionClient());
    }

    @Test
    void testBuilder_WithLambdaClientBuilder_NullThrowsException() {
        var builder = DurableConfig.builder();

        var exception = assertThrows(NullPointerException.class, () -> builder.withLambdaClientBuilder(null));

        assertEquals("LambdaClient cannot be null", exception.getMessage());
    }

    @Test
    void testAddUserAgentSuffix_SetsUserAgentOnBuilder() {
        var lambdaClientBuilder = LambdaClient.builder();

        var result = DurableConfig.addUserAgentSuffix(lambdaClientBuilder);

        var overrideConfig = result.overrideConfiguration();
        var userAgentSuffix = overrideConfig.advancedOption(SdkAdvancedClientOption.USER_AGENT_SUFFIX);
        assertTrue(userAgentSuffix.isPresent(), "USER_AGENT_SUFFIX should be set");
        assertTrue(
                userAgentSuffix.get().contains("@aws/durable-execution-sdk-java/"),
                "User agent suffix should contain SDK identifier, got: " + userAgentSuffix.get());
    }

    @Test
    void testAddUserAgentSuffix_PreservesExistingConfiguration() {
        var lambdaClientBuilder =
                LambdaClient.builder().overrideConfiguration(c -> c.putHeader("X-Custom-Header", "test-value"));

        var result = DurableConfig.addUserAgentSuffix(lambdaClientBuilder);

        var overrideConfig = result.overrideConfiguration();
        var userAgentSuffix = overrideConfig.advancedOption(SdkAdvancedClientOption.USER_AGENT_SUFFIX);
        assertTrue(userAgentSuffix.isPresent());
        assertTrue(userAgentSuffix.get().contains("@aws/durable-execution-sdk-java/"));
    }

    @Test
    void testAddUserAgentSuffix_ReturnsSameBuilderInstance() {
        var lambdaClientBuilder = LambdaClient.builder();

        var result = DurableConfig.addUserAgentSuffix(lambdaClientBuilder);

        assertSame(lambdaClientBuilder, result);
    }

    // --- Polling strategy tests ---

    @Test
    void testDefaultConfig_PollingStrategyDefaults() {
        var config = DurableConfig.defaultConfig();

        assertNotNull(config.getPollingStrategy());
        assertSame(PollingStrategies.Presets.DEFAULT, config.getPollingStrategy());
        assertEquals(Duration.ofSeconds(0), config.getCheckpointDelay());
    }

    @Test
    void testBuilder_WithCustomPollingStrategy() {
        var customStrategy = PollingStrategies.exponentialBackoff(
                Duration.ofMillis(500), 3.0, JitterStrategy.NONE, Duration.ofSeconds(10));
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withPollingStrategy(customStrategy)
                .build();

        assertSame(customStrategy, config.getPollingStrategy());
    }

    @Test
    void testBuilder_WithFixedDelayPollingStrategy() {
        var fixedStrategy = PollingStrategies.fixedDelay(Duration.ofMillis(200));
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withPollingStrategy(fixedStrategy)
                .build();

        assertSame(fixedStrategy, config.getPollingStrategy());
    }

    @Test
    void testBuilder_WithPollingStrategyNull_UsesDefault() {
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withPollingStrategy(null)
                .build();

        assertSame(PollingStrategies.Presets.DEFAULT, config.getPollingStrategy());
    }

    @Test
    void testBuilder_WithCustomCheckpointDelay() {
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withCheckpointDelay(Duration.ofSeconds(5))
                .build();

        assertEquals(Duration.ofSeconds(5), config.getCheckpointDelay());
    }

    @Test
    void testBuilder_WithPollingStrategyAndCheckpointDelay() {
        var customStrategy = PollingStrategies.exponentialBackoff(
                Duration.ofMillis(200), 1.5, JitterStrategy.HALF, Duration.ofSeconds(10));
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withPollingStrategy(customStrategy)
                .withCheckpointDelay(Duration.ofSeconds(2))
                .build();

        assertSame(customStrategy, config.getPollingStrategy());
        assertEquals(Duration.ofSeconds(2), config.getCheckpointDelay());
    }

    @Test
    void testBuilder_FluentAPI_PollingMethods() {
        var builder = DurableConfig.builder();
        var strategy = PollingStrategies.Presets.DEFAULT;

        assertSame(builder, builder.withPollingStrategy(strategy));
        assertSame(builder, builder.withCheckpointDelay(Duration.ofSeconds(1)));
    }

    @Test
    void testBuilder_CheckpointDelayNull_UsesDefault() {
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withCheckpointDelay(null)
                .build();

        assertEquals(Duration.ofSeconds(0), config.getCheckpointDelay());
    }

    // --- validateConfiguration tests ---

    @Test
    void validateConfiguration_PassesForValidConfig() {
        var config = DurableConfig.builder()
                .withDurableExecutionClient(mockClient)
                .withSerDes(mockSerDes)
                .withExecutorService(mockExecutor)
                .build();

        // Should not throw — all fields are set
        config.validateConfiguration();
    }

    @Test
    void validateConfiguration_ThrowsWhenDurableExecutionClientIsNull() throws Exception {
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient).build();

        setField(config, "durableExecutionClient", null);

        var ex = assertThrows(IllegalStateException.class, config::validateConfiguration);
        assertEquals("DurableExecutionClient configuration failed", ex.getMessage());
    }

    @Test
    void validateConfiguration_ThrowsWhenSerDesIsNull() throws Exception {
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient).build();

        setField(config, "serDes", null);

        var ex = assertThrows(IllegalStateException.class, config::validateConfiguration);
        assertEquals("SerDes configuration failed", ex.getMessage());
    }

    @Test
    void validateConfiguration_ThrowsWhenExecutorServiceIsNull() throws Exception {
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient).build();

        setField(config, "executorService", null);

        var ex = assertThrows(IllegalStateException.class, config::validateConfiguration);
        assertEquals("ExecutorService configuration failed", ex.getMessage());
    }

    @Test
    void validateConfiguration_ChecksClientBeforeSerDes() throws Exception {
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient).build();

        setField(config, "durableExecutionClient", null);
        setField(config, "serDes", null);

        var ex = assertThrows(IllegalStateException.class, config::validateConfiguration);
        assertEquals("DurableExecutionClient configuration failed", ex.getMessage());
    }

    @Test
    void validateConfiguration_ChecksSerDesBeforeExecutorService() throws Exception {
        var config =
                DurableConfig.builder().withDurableExecutionClient(mockClient).build();

        setField(config, "serDes", null);
        setField(config, "executorService", null);

        var ex = assertThrows(IllegalStateException.class, config::validateConfiguration);
        assertEquals("SerDes configuration failed", ex.getMessage());
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
