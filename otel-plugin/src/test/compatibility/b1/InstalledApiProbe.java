// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.TestResult;

/** Compiled against the actual released 2.2.1 SPI, then run unchanged in fresh matrix JVMs. */
public final class InstalledApiProbe {
    private static final AtomicInteger HEALTHY_CREATED = new AtomicInteger();
    private static final AtomicInteger HEALTHY_STARTS = new AtomicInteger();
    private static final AtomicInteger HEALTHY_ENDS = new AtomicInteger();

    private InstalledApiProbe() {}

    public static void main(String[] args) throws Exception {
        verifyArtifacts(args);
        var view = args[4];
        var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        root.addAppender(logs);
        try {
            exercise(view, Boolean.parseBoolean(args[5]), Boolean.parseBoolean(args[6]), logs);
            System.out.println("COMPAT_PASS " + view + " negative=" + args[5] + " api=" + args[2]
                    + " core=" + args[0] + " plugin=" + args[1]);
        } finally {
            root.detachAppender(logs);
            logs.stop();
            GlobalOpenTelemetry.resetForTest();
            OtelPluginAutoConfigurationState.resetInstalledForTest();
        }
    }

    private static void verifyArtifacts(String[] args) throws Exception {
        checkSource(DurableExecutionPlugin.class, Path.of(args[0]));
        checkSource(GlobalOpenTelemetry.class, Path.of(args[2]));
        checkSource(Context.class, Path.of(args[3]));
        var provider = ServiceLoader.load(DurableExecutionPluginProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(value -> value.getName().equals(args[4]))
                .findFirst().orElseThrow();
        check(provider.getApiVersion() == DurableExecutionPluginProvider.API_VERSION, "released SPI version");
        checkSource(provider.getPluginType(), Path.of(args[1]));
    }

    private static void exercise(String view, boolean negative, boolean compatible, ListAppender<ILoggingEvent> logs) {
        GlobalOpenTelemetry.resetForTest();
        // Reproduce #763's documented visible-API skew, not a claim of a deployed agent test.
        OtelPluginAutoConfigurationState.markInstalled();
        var handlerCalls = new AtomicInteger();
        var sideEffects = new AtomicInteger();
        var runner = createRunner(handlerCalls, sideEffects);
        check(HEALTHY_CREATED.get() == 1, "existing SPI must create the healthy plugin once per configuration");
        var first = runner.run("compatibility-input");
        if (negative) {
            assertNegative(first, handlerCalls, sideEffects);
            return;
        }
        check(first.getStatus() == ExecutionStatus.PENDING, "plugin failure must preserve first invocation");
        check(HEALTHY_STARTS.get() == 1 && HEALTHY_ENDS.get() == 1 && handlerCalls.get() == 1,
                "healthy plugin and handler must run");
        if (!compatible) {
            synchronized (logs) {
                check(hasCompatibilityDiagnostic(List.copyOf(logs.list)), "incompatible API must be diagnosed");
            }
        }
        resumeAndCheck(runner, view, compatible, handlerCalls, sideEffects);
    }

    private static LocalDurableTestRunner<String, String> createRunner(
            AtomicInteger handlerCalls, AtomicInteger sideEffects) {
        return LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            handlerCalls.incrementAndGet();
            var saved = ctx.step("saved", String.class, step -> {
                sideEffects.incrementAndGet();
                return input;
            });
            ctx.wait("resume", Duration.ofSeconds(1));
            return saved;
        });
    }

    private static void assertNegative(
            TestResult<String> result, AtomicInteger handlerCalls, AtomicInteger sideEffects) {
        check(result.getStatus() == ExecutionStatus.FAILED, "old/old older API must reproduce customer failure");
        var failure = result.getError().orElseThrow();
        check(failure.errorType().endsWith("NoSuchMethodError")
                        && failure.errorMessage().contains("GlobalOpenTelemetry.isSet"),
                "negative control must reproduce the exact unsupported API: " + failure);
        check(HEALTHY_STARTS.get() == 0 && handlerCalls.get() == 0 && sideEffects.get() == 0,
                "old linkage failure must precede the healthy start hook and handler");
        System.out.println("NEGATIVE_CONTROL_REPRODUCED NoSuchMethodError GlobalOpenTelemetry.isSet");
    }

    private static void resumeAndCheck(LocalDurableTestRunner<String, String> runner, String view, boolean compatible,
            AtomicInteger handlerCalls, AtomicInteger sideEffects) {
        var exporter = InMemorySpanExporter.create();
        var tracing = registerLateGlobal(compatible, exporter);
        try {
            runner.advanceTime();
            var last = runner.runUntilComplete("compatibility-input");
            check(last.getStatus() == ExecutionStatus.SUCCEEDED, "handler must complete after resume");
            check("compatibility-input".equals(last.getResult(String.class)), "handler output must be preserved");
            check(HEALTHY_STARTS.get() == 2 && HEALTHY_ENDS.get() == 2 && handlerCalls.get() == 2,
                    "healthy hooks and handler must remain active on resume");
            check(sideEffects.get() == 1, "completed user step must not repeat on resume");
            check(HEALTHY_CREATED.get() == 1, "resume must preserve the existing 2.x plugin instance lifetime");
            var spans = exporter.getFinishedSpanItems();
            if (compatible) check(spans.stream().anyMatch(span -> span.getName().equals("Workflow")),
                    "compatible global provider must export Workflow spans in " + view);
            else check(spans.isEmpty(), "unsupported global API must disable the affected instrumentation");
        } finally {
            if (tracing != null) tracing.close();
        }
    }

    private static SdkTracerProvider registerLateGlobal(boolean compatible, InMemorySpanExporter exporter) {
        // Registration must succeed: an early plugin must not freeze the global as no-op.
        if (!compatible) {
            GlobalOpenTelemetry.set(OpenTelemetry.noop());
            return null;
        }
        var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        DeterministicIdGenerator.installOn(builder);
        DurableSampler.installOn(builder);
        var tracing = builder.build();
        OpenTelemetrySdk.builder().setTracerProvider(tracing).buildAndRegisterGlobal();
        return tracing;
    }

    /** A real old-SPI service provider, discovered alongside the actual released/candidate OTel provider. */
    public static final class HealthyProvider implements DurableExecutionPluginProvider {
        @Override
        public String getName() { return "compat-healthy"; }

        @Override
        public int getApiVersion() { return API_VERSION; }

        @Override
        public Class<? extends DurableExecutionPlugin> getPluginType() { return HealthyPlugin.class; }

        @Override
        public DurableExecutionPlugin createPlugin() {
            HEALTHY_CREATED.incrementAndGet();
            return new HealthyPlugin();
        }
    }

    public static final class HealthyPlugin implements DurableExecutionPlugin {
        @Override
        public void onInvocationStart(InvocationInfo info) { HEALTHY_STARTS.incrementAndGet(); }

        @Override
        public void onInvocationEnd(InvocationEndInfo info) { HEALTHY_ENDS.incrementAndGet(); }
    }

    private static boolean hasCompatibilityDiagnostic(List<ILoggingEvent> events) {
        return events.stream().anyMatch(event -> {
            var text = event.getFormattedMessage();
            var error = event.getThrowableProxy();
            if (error != null) text += " " + error.getClassName() + " " + error.getMessage();
            return event.getLevel().isGreaterOrEqual(Level.WARN)
                    && text.contains("OpenTelemetry")
                    && (text.contains("API") || text.contains("isSet") || text.contains("getOrNoop")
                            || text.contains("NoSuchMethodError"));
        });
    }

    private static void checkSource(Class<?> type, Path expected) throws Exception {
        var actual = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
        check(actual.equals(expected.toRealPath()), type.getName() + " loaded from wrong artifact: " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
