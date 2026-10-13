// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.PluginRunner;

/** Compiled against the supplied released core; optional core features are inspected reflectively. */
public class PluginLayerCompatibilityProbe {
    private static final Instant EXECUTION_START = Instant.parse("2026-01-01T00:00:00Z");
    private static final long EXECUTION_START_NANOS = EXECUTION_START.getEpochSecond() * 1_000_000_000L;
    private static final String PROPERTY = "com.amazonaws.xray.traceHeader";
    private static final String TRACE = "6955b900123456789012345678901234";
    private static final String PARENT = "1234567890123456";
    private static final String HEADER = "Root=1-6955b900-123456789012345678901234;Parent=" + PARENT + ";Sampled=1";
    private static final String CAPABILITY_DIAGNOSTIC =
            "This OpenTelemetry plugin requires same-thread invocation hooks from the Durable Execution"
                    + " core (2.2.2 lifecycle capability). Upgrade the core together with the plugin layer.";

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            checkCreationControls();
            return;
        }
        var core = Path.of(args[0]).toRealPath();
        var path = Path.of(args[1]).toRealPath();
        var view = args[2];
        var contract = args[3];
        if (!Set.of("released", "current").contains(contract)) throw new AssertionError("unknown plugin contract");
        boolean currentPlugin = contract.equals("current");
        checkCodeSource(DurableExecutionPlugin.class, core);
        var coreType = Class.forName("software.amazon.lambda.durable.execution.DurableExecutor");
        checkCodeSource(coreType, core);
        boolean capability = supportsLifecycle(coreType);
        var old = System.getProperty(PROPERTY);
        try (var layer = new URLClassLoader(
                new URL[] {path.toUri().toURL()}, PluginLayerCompatibilityProbe.class.getClassLoader())) {
            System.setProperty(PROPERTY, HEADER);
            var provider = ServiceLoader.load(DurableExecutionPluginProvider.class, layer).stream()
                    .map(ServiceLoader.Provider::get)
                    .filter(p -> p.getName().equals(view))
                    .findFirst()
                    .orElseThrow();
            if (provider.getApiVersion() != DurableExecutionPluginProvider.API_VERSION)
                throw new AssertionError("provider API");
            var type = provider.getPluginType();
            if (type.getClassLoader() != layer || provider.getClass().getClassLoader() != layer)
                throw new AssertionError("plugin/provider did not load from separate layer");
            checkCodeSource(type, path);
            checkCodeSource(provider.getClass(), path);
            System.out.println("ARTIFACT core=" + core + " layer=" + path);
            if (!checkCreation(provider::createPlugin, currentPlugin && !capability)) {
                System.out.println("CAPABILITY_DIAGNOSTIC " + CAPABILITY_DIAGNOSTIC);
                printResult("EXPECTED_UNSUPPORTED", view, contract, capability);
                return;
            }
            checkTracing(layer, type, currentPlugin);
            printResult("SUPPORTED", view, contract, capability);
        } finally {
            if (old == null) System.clearProperty(PROPERTY);
            else System.setProperty(PROPERTY, old);
        }
    }

    private static boolean supportsLifecycle(Class<?> core) throws Exception {
        try {
            return (boolean) core.getMethod("supportsSameThreadInvocationHooks").invoke(null);
        } catch (NoSuchMethodException missingCapability) {
            return false;
        }
    }

    private static void checkCodeSource(Class<?> type, Path expected) throws Exception {
        var actual = Path.of(
                        type.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toRealPath();
        if (!actual.equals(expected))
            throw new AssertionError(type.getName() + " loaded from " + actual + ", expected " + expected);
    }

    private static boolean checkCreation(Supplier<DurableExecutionPlugin> create, boolean expectedUnsupported) {
        DurableExecutionPlugin plugin;
        try {
            plugin = create.get();
        } catch (IllegalStateException failure) {
            if (!expectedUnsupported) throw failure;
            if (failure.getClass() != IllegalStateException.class
                    || !CAPABILITY_DIAGNOSTIC.equals(failure.getMessage()))
                throw new AssertionError("wrong capability rejection diagnostic", failure);
            return false;
        }
        if (expectedUnsupported) throw new AssertionError("unsupported constructor unexpectedly succeeded");
        if (plugin == null) throw new AssertionError("dynamic provider constructor returned null");
        return true;
    }

    private static void checkTracing(URLClassLoader layer, Class<?> type, boolean currentPlugin) throws Exception {
        var configType = layer.loadClass("software.amazon.lambda.durable.otel.OtelPluginConfig");
        var configBuilder = configType.getMethod("builder").invoke(null);
        configBuilder.getClass().getMethod("enableMdc", boolean.class).invoke(configBuilder, false);
        var config = configBuilder.getClass().getMethod("build").invoke(configBuilder);
        var exporter = InMemorySpanExporter.create();
        var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
        var plugin = (DurableExecutionPlugin)
                type.getConstructor(SdkTracerProviderBuilder.class, configType).newInstance(builder, config);
        var runner = new PluginRunner(List.of(plugin));
        invoke(runner, new InvocationInfo("request", "arn:exec", true, EXECUTION_START));
        checkSpans(exporter.getFinishedSpanItems(), TRACE, PARENT, false);
        System.out.println("CHECK remote-parent names=Invocation,Workflow trace=" + TRACE + " parent=" + PARENT);
        type.getMethods(); // Public reflection must also work for the legacy-compiled caller.
        if (currentPlugin) {
            exporter.reset();
            System.setProperty(PROPERTY, "Root=1-6955b900-123456789012345678901234;Sampled=1");
            invoke(runner, new InvocationInfo("root-only", "arn:exec", false, EXECUTION_START));
            checkSpans(exporter.getFinishedSpanItems(), TRACE, null, true);
            System.out.println("CHECK synthetic-root names=DurableExecutionRoot,Invocation,Workflow trace=" + TRACE);
        }
        System.setProperty(PROPERTY, HEADER);
        checkOptionalHeaderDispatch(runner, exporter, currentPlugin);
    }

    private static void invoke(PluginRunner runner, InvocationInfo info) {
        runner.onInvocationStart(info);
        runner.onInvocationEnd(new InvocationEndInfo(
                info.requestId(),
                info.durableExecutionArn(),
                info.isFirstInvocation(),
                InvocationStatus.SUCCEEDED,
                null));
    }

    private static void checkSpans(List<SpanData> spans, String traceId, String parent, boolean syntheticRoot) {
        var names = syntheticRoot
                ? Set.of("DurableExecutionRoot", "Invocation", "Workflow")
                : Set.of("Invocation", "Workflow");
        if (spans.size() != names.size()
                || !spans.stream()
                        .map(SpanData::getName)
                        .collect(Collectors.toSet())
                        .equals(names)) throw new AssertionError("unexpected exported span names/count: " + spans);
        if (spans.stream()
                .anyMatch(s -> !s.getSpanContext().isValid()
                        || !s.getTraceId().equals(traceId)
                        || !s.getSpanContext().isSampled()))
            throw new AssertionError("invalid trace or sampling: " + spans);
        if (syntheticRoot) {
            var root = spans.stream()
                    .filter(s -> s.getName().equals("DurableExecutionRoot"))
                    .findFirst()
                    .orElseThrow();
            if (root.getParentSpanContext().isValid()
                    || root.getStartEpochNanos() != EXECUTION_START_NANOS
                    || root.getEndEpochNanos() != EXECUTION_START_NANOS
                    || !Boolean.TRUE.equals(
                            root.getAttributes().get(AttributeKey.booleanKey("durable.execution.synthetic_root"))))
                throw new AssertionError("invalid zero-duration synthetic root: " + root);
            parent = root.getSpanId();
        }
        for (var span : spans) {
            if (!span.getName().equals("DurableExecutionRoot")
                    && !span.getParentSpanId().equals(parent))
                throw new AssertionError("wrong parent for " + span.getName() + ": " + span);
        }
    }

    private static void checkOptionalHeaderDispatch(
            PluginRunner runner, InMemorySpanExporter exporter, boolean currentPlugin) throws Exception {
        Constructor<InvocationInfo> constructor;
        try {
            constructor = InvocationInfo.class.getConstructor(
                    String.class,
                    String.class,
                    boolean.class,
                    Instant.class,
                    Object.class,
                    Map.class,
                    Map.class,
                    String.class);
        } catch (NoSuchMethodException olderCore) {
            System.out.println("CHECK runtime-header=unavailable legacy-carrier-verified");
            return;
        }
        for (var sampled : List.of("1", "0")) {
            exporter.reset();
            var info = constructor.newInstance(
                    "header-" + sampled,
                    "arn:exec",
                    false,
                    EXECUTION_START,
                    null,
                    Map.of(),
                    Map.of(),
                    "Root=1-6955b900-aaaaaaaaaaaaaaaaaaaaaaaa;Parent=abcdefabcdefabcd;Sampled=" + sampled);
            invoke(runner, info);
            if (currentPlugin && sampled.equals("0")) {
                if (!exporter.getFinishedSpanItems().isEmpty())
                    throw new AssertionError("unsampled runtime header exported spans");
            } else {
                checkSpans(
                        exporter.getFinishedSpanItems(),
                        currentPlugin ? "6955b900aaaaaaaaaaaaaaaaaaaaaaaa" : TRACE,
                        currentPlugin ? "abcdefabcdefabcd" : PARENT,
                        false);
            }
        }
        System.out.println("CHECK runtime-header="
                + (currentPlugin ? "sampled-parent-and-unsampled-override" : "legacy-carrier-retained"));
    }

    private static void printResult(String status, String view, String contract, boolean capability) {
        System.out.println("COMPATIBILITY_RESULT status=" + status + " view=" + view + " contract=" + contract
                + " lifecycle=" + capability);
    }

    private static void checkCreationControls() {
        Supplier<DurableExecutionPlugin> success = () -> new DurableExecutionPlugin() {};
        Supplier<DurableExecutionPlugin> rejection = () -> {
            throw new IllegalStateException(CAPABILITY_DIAGNOSTIC);
        };
        if (checkCreation(rejection, true) || !checkCreation(success, false))
            throw new AssertionError("creation control");
        expectFailure(
                AssertionError.class,
                () -> checkCreation(
                        () -> {
                            throw new IllegalStateException("wrong diagnostic");
                        },
                        true));
        expectFailure(
                UnsupportedOperationException.class,
                () -> checkCreation(
                        () -> {
                            throw new UnsupportedOperationException("unrelated constructor failure");
                        },
                        true));
        expectFailure(AssertionError.class, () -> checkCreation(success, true));
        expectFailure(IllegalStateException.class, () -> checkCreation(rejection, false));
        System.out.println(
                "PASS creation-controls exact-diagnostic wrong-diagnostic unrelated-failure unexpected-success supported-failure");
    }

    private static void expectFailure(Class<? extends Throwable> expected, Runnable check) {
        try {
            check.run();
        } catch (Throwable failure) {
            if (failure.getClass() == expected) return;
            throw new AssertionError("wrong control failure", failure);
        }
        throw new AssertionError("control unexpectedly passed");
    }
}
