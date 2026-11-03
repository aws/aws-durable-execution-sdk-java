// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import java.time.Instant;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationInfo;

class OtelPluginTest {
    @AfterEach
    void resetGlobal() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void allDefaultOverloadsSelectExecution() {
        var factories = List.of(
                OtelPlugin.factory(),
                OtelPlugin.factory(OtelPluginConfig.defaults()),
                OtelPlugin.factory(SdkTracerProvider.builder()),
                OtelPlugin.factory(SdkTracerProvider.builder(), OtelPluginConfig.defaults()));
        for (var factory : factories) {
            assertEquals("ExecutionOtelPlugin", factory.toString());
            assertEquals("durable-otel-view", factory.getExclusiveGroup());
        }
    }

    @ParameterizedTest
    @EnumSource(OtelPlugin.Mode.class)
    void explicitModeOverloadsRetainTheSelectedFactoryMetadata(OtelPlugin.Mode mode) {
        var factories = List.of(
                OtelPlugin.factory(mode),
                OtelPlugin.factory(mode, OtelPluginConfig.defaults()),
                OtelPlugin.factory(mode, SdkTracerProvider.builder()),
                OtelPlugin.factory(mode, SdkTracerProvider.builder(), OtelPluginConfig.defaults()));
        var expected = mode == OtelPlugin.Mode.EXECUTION ? "ExecutionOtelPlugin" : "InvocationOtelPlugin";
        for (var factory : factories) {
            assertEquals(expected, factory.toString());
            assertEquals("durable-otel-view", factory.getExclusiveGroup());
        }
    }

    @Test
    void globalFactoriesAndProviderDiscoveryDoNotInitializeGlobalTelemetry() {
        GlobalOpenTelemetry.resetForTest();
        OtelPlugin.factory();
        OtelPlugin.factory(OtelPlugin.Mode.INVOCATION);
        ServiceLoader.load(DurableExecutionPluginProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertFalse(GlobalOpenTelemetry.isSet());
    }

    @Test
    void nullModeIsRejectedBeforeProviderBuilderIsTouched() {
        var builder = mock(SdkTracerProviderBuilder.class);
        var settings = OtelPluginConfig.defaults();
        assertThrows(NullPointerException.class, () -> OtelPlugin.factory((OtelPlugin.Mode) null));
        assertThrows(NullPointerException.class, () -> OtelPlugin.factory((OtelPlugin.Mode) null, settings));
        assertThrows(NullPointerException.class, () -> OtelPlugin.factory(null, builder));
        var error = assertThrows(NullPointerException.class, () -> OtelPlugin.factory(null, builder, settings));
        assertEquals("mode must not be null", error.getMessage());
        verifyNoInteractions(builder);
    }

    @ParameterizedTest
    @EnumSource(OtelPlugin.Mode.class)
    void nullConfigurationRetainsExistingValidation(OtelPlugin.Mode mode) {
        assertThrows(NullPointerException.class, () -> OtelPlugin.factory(mode, (OtelPluginConfig) null));
        assertThrows(NullPointerException.class, () -> OtelPlugin.factory(mode, SdkTracerProvider.builder(), null));
    }

    @Test
    void serviceLoaderDiscoversNewAndExistingAliases() {
        var providers = ServiceLoader.load(DurableExecutionPluginProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
        assertTrue(providers.stream().anyMatch(provider -> provider.getName().equals("otel-execution")));
        assertTrue(providers.stream().anyMatch(provider -> provider.getName().equals("otel-invocation")));
        var provider = providers.stream()
                .filter(candidate -> candidate.getName().equals("otel"))
                .findFirst()
                .orElseThrow();
        assertEquals("durable-otel-view", provider.getExclusiveGroup());
        assertExecutionInstancesAreDistinct(provider);
    }

    private static void assertExecutionInstancesAreDistinct(DurableExecutionPluginFactory factory) {
        var info = new InvocationInfo("request", "arn:execution", true, Instant.now());
        var first = factory.createPlugin(info);
        var second = factory.createPlugin(info);
        assertInstanceOf(ExecutionOtelPlugin.class, first);
        assertInstanceOf(ExecutionOtelPlugin.class, second);
        assertNotSame(first, second);
    }
}
