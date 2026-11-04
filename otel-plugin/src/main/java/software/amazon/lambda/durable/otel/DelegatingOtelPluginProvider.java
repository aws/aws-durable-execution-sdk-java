// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationInfo;

/** Delegates a named ServiceLoader entry to its view's per-invocation factory. */
final class DelegatingOtelPluginProvider implements DurableExecutionPluginProvider {
    private final String name;
    private final DurableExecutionPluginFactory factory;

    DelegatingOtelPluginProvider(String name, DurableExecutionPluginFactory factory) {
        this.name = name;
        this.factory = factory;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo) {
        return factory.createPlugin(invocationInfo);
    }

    @Override
    public String getExclusiveGroup() {
        return factory.getExclusiveGroup();
    }

    /**
     * Names the selected entry and the view it loads, so an exclusive-group conflict reports the
     * {@code DURABLE_EXECUTION_PLUGINS} value the operator set rather than an object identity hash.
     */
    @Override
    public String toString() {
        return getName() + " (" + factory + ")";
    }
}
