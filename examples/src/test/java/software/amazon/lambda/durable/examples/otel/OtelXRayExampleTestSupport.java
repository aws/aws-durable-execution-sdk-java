// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

final class OtelXRayExampleTestSupport {

    private static final String SPI_INSTALLED_PROPERTY =
            "software.amazon.lambda.durable.otel.autoConfigurationCustomizerProviderInstalled";

    private OtelXRayExampleTestSupport() {}

    static void installGlobalOpenTelemetry() {
        System.setProperty(SPI_INSTALLED_PROPERTY, Boolean.TRUE.toString());
        var tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .build();
        OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).buildAndRegisterGlobal();
    }

    static void resetGlobalOpenTelemetry() {
        GlobalOpenTelemetry.resetForTest();
        System.clearProperty(SPI_INSTALLED_PROPERTY);
    }
}
