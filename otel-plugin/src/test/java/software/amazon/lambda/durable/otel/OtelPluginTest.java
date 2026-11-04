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
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationInfo;

class OtelPluginTest {
    @AfterEach
    void resetGlobal() {
        GlobalOpenTelemetry.resetForTest();
    }

    @Test
    void noArgumentFactorySelectsExecution() {
        var factory = OtelPlugin.factory();
        assertEquals("ExecutionOtelPlugin", factory.toString());
        assertEquals("durable-otel-view", factory.getExclusiveGroup());
    }

    @Test
    void repeatedCallsReturnDistinctFactoriesThatCannotBothBeRegistered() {
        var first = OtelPlugin.factory();
        var second = OtelPlugin.factory();
        assertNotSame(first, second);
        var conflict = assertThrows(
                IllegalArgumentException.class,
                () -> DurableConfig.builder().withPlugins(first, second).build());
        assertTrue(conflict.getMessage().contains("durable-otel-view"), conflict.getMessage());
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
        // Mode is the only single-argument overload, so an untyped null needs no cast to resolve.
        assertThrows(NullPointerException.class, () -> OtelPlugin.factory(null));
        assertThrows(NullPointerException.class, () -> OtelPlugin.factory(null, settings));
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

    @Test
    void providerDescriptionsNameTheConfiguredEntryAndItsView() {
        var described = ServiceLoader.load(DurableExecutionPluginProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.getName().startsWith("otel"))
                .collect(Collectors.toMap(DurableExecutionPluginProvider::getName, Object::toString));
        assertEquals("otel (ExecutionOtelPlugin)", described.get("otel"));
        assertEquals("otel-execution (ExecutionOtelPlugin)", described.get("otel-execution"));
        assertEquals("otel-invocation (InvocationOtelPlugin)", described.get("otel-invocation"));
    }

    @Test
    void conflictingAliasesReportTheEntryNamesTheOperatorSet() {
        var providers = ServiceLoader.load(DurableExecutionPluginProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(candidate -> candidate.getName().equals("otel")
                        || candidate.getName().equals("otel-execution"))
                .map(DurableExecutionPluginFactory.class::cast)
                .toList();
        assertEquals(2, providers.size());
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> DurableConfig.builder()
                        .withPlugins(providers.toArray(DurableExecutionPluginFactory[]::new))
                        .build());
        assertTrue(error.getMessage().contains("otel (ExecutionOtelPlugin)"), error.getMessage());
        assertTrue(error.getMessage().contains("otel-execution (ExecutionOtelPlugin)"), error.getMessage());
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
