// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package io.opentelemetry.javaagent.testing;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerBuilder;
import io.opentelemetry.api.trace.TracerProvider;

public final class FakeJavaAgentTracerProvider implements TracerProvider {

    private final TracerProvider delegate;

    public FakeJavaAgentTracerProvider(TracerProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public Tracer get(String instrumentationName) {
        return delegate.get(instrumentationName);
    }

    @Override
    public Tracer get(String instrumentationName, String instrumentationVersion) {
        return delegate.get(instrumentationName, instrumentationVersion);
    }

    @Override
    public TracerBuilder tracerBuilder(String instrumentationScopeName) {
        return delegate.tracerBuilder(instrumentationScopeName);
    }
}
