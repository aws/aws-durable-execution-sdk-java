// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package io.opentelemetry.javaagent.testing;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;

public final class FakeJavaAgentTracerProvider implements TracerProvider {

    private final TracerProvider agentTracerProvider;

    public FakeJavaAgentTracerProvider(TracerProvider agentTracerProvider) {
        this.agentTracerProvider = agentTracerProvider;
    }

    @Override
    public Tracer get(String instrumentationName) {
        return agentTracerProvider.get(instrumentationName);
    }

    @Override
    public Tracer get(String instrumentationName, String instrumentationVersion) {
        return agentTracerProvider.get(instrumentationName, instrumentationVersion);
    }

    @Override
    public TracerBuilder tracerBuilder(String instrumentationScopeName) {
        return agentTracerProvider.tracerBuilder(instrumentationScopeName);
    }
}
