// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PropagationMetadataCollectorTest {
    private static final PropagationInput INPUT = new PropagationInput("arn", "operation", "parent", "target:alias");

    @Test
    void legacyPluginsRemainNoOpForMetadataAndKeepTheirHooks() {
        var calls = new AtomicInteger();
        var legacy = new DurableExecutionPlugin() {
            @Override
            public void onInvocationStart(InvocationInfo info) {
                calls.incrementAndGet();
            }
        };
        var runner = new PluginRunner(List.of(legacy));
        assertNull(runner.providePropagationMetadata(INPUT));
        runner.onInvocationStart(new InvocationInfo("request", "arn", true, Instant.EPOCH));
        assertEquals(1, calls.get());
        assertNull(PluginRunner.noOp().providePropagationMetadata(INPUT));
    }

    @Test
    void configuredOrderFirstNonNullWinsWithoutSkippingOtherPlugins() {
        var seen = new ArrayList<String>();
        var thread = Thread.currentThread();
        var first = new DurableExecutionPlugin() {
            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                assertSame(INPUT, input);
                assertSame(thread, Thread.currentThread());
                seen.add(input.executionArn() + ":" + input.operationId() + ":" + input.parentOperationId() + ":"
                        + input.targetFunctionName());
                return new PropagationMetadata("first");
            }
        };
        var runner = new PluginRunner(List.of(
                new DurableExecutionPlugin() {},
                first,
                plugin("equal", "first", seen),
                plugin("conflict", "later", seen),
                plugin("last", null, seen)));
        assertEquals("first", runner.providePropagationMetadata(INPUT).xAmznTraceId());
        assertEquals(List.of("arn:operation:parent:target:alias", "equal", "conflict", "last"), seen);
    }

    @Test
    void ordinaryFailuresInvalidContributionsAndCancellationKeepHealthyPluginsRunning() {
        var seen = new ArrayList<String>();
        var failure = new DurableExecutionPlugin() {
            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                throw new IllegalStateException("optional instrumentation failed");
            }
        };
        var cancellation = new DurableExecutionPlugin() {
            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                throw new CancellationException("optional instrumentation cancelled");
            }
        };
        var runner = new PluginRunner(List.of(
                failure, cancellation, plugin("invalid", " ", seen), plugin("healthy", "healthy-header", seen)));
        assertEquals("healthy-header", runner.providePropagationMetadata(INPUT).xAmznTraceId());
        assertEquals(List.of("invalid", "healthy"), seen);
    }

    @Test
    void errorPropagationMatchesExistingPluginBoundary() {
        var fatal = new AssertionError("not an ordinary Exception");
        var plugin = new DurableExecutionPlugin() {
            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                throw fatal;
            }
        };
        assertSame(
                fatal,
                assertThrows(
                        AssertionError.class,
                        () -> new PluginRunner(List.of(plugin)).providePropagationMetadata(INPUT)));
    }

    @Test
    void invalidSdkInputCannotDescribeAnOperation() {
        assertThrows(NullPointerException.class, () -> new PropagationInput(null, "op", null, "target"));
        assertThrows(IllegalArgumentException.class, () -> new PropagationInput("arn", "", null, "target"));
        assertThrows(IllegalArgumentException.class, () -> new PropagationInput("arn", "op", null, " "));
    }

    private static DurableExecutionPlugin plugin(String identity, String header, List<String> calls) {
        return new DurableExecutionPlugin() {
            @Override
            public PropagationMetadata providePropagationMetadata(PropagationInput input) {
                assertSame(INPUT, input);
                calls.add(identity);
                return new PropagationMetadata(header);
            }
        };
    }
}
