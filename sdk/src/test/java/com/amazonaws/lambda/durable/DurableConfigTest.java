// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.client.LambdaDurableFunctionsClient;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
}
