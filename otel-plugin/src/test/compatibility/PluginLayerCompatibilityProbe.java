// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.ServiceLoader;
import software.amazon.lambda.durable.plugin.*;

public class PluginLayerCompatibilityProbe {
    public static void main(String[] args) throws Exception {
        var path = Path.of(args[0]);
        var view = args[1];
        var header = "Root=1-6955b900-123456789012345678901234;Parent=1234567890123456;Sampled=1";
        var old = System.getProperty("com.amazonaws.xray.traceHeader");
        try (var layer = new URLClassLoader(new URL[] {path.toUri().toURL()},
                PluginLayerCompatibilityProbe.class.getClassLoader())) {
            System.setProperty("com.amazonaws.xray.traceHeader", header);
            var provider = ServiceLoader.load(DurableExecutionPluginProvider.class, layer).stream()
                    .map(ServiceLoader.Provider::get).filter(p -> p.getName().equals(view)).findFirst().orElseThrow();
            if (provider.getApiVersion() != DurableExecutionPluginProvider.API_VERSION) throw new AssertionError("provider API");
            var type = provider.getPluginType();
            if (type.getClassLoader() != layer) throw new AssertionError("plugin did not load from separate layer");
            if (provider.createPlugin() == null) throw new AssertionError("dynamic provider constructor");
            var configType = layer.loadClass("software.amazon.lambda.durable.otel.OtelPluginConfig");
            var configBuilder = configType.getMethod("builder").invoke(null);
            configBuilder.getClass().getMethod("enableMdc", boolean.class).invoke(configBuilder, false);
            var config = configBuilder.getClass().getMethod("build").invoke(configBuilder);
            var exporter = InMemorySpanExporter.create();
            var builder = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter));
            var plugin = (DurableExecutionPlugin) type.getConstructor(SdkTracerProviderBuilder.class, configType)
                    .newInstance(builder, config);
            var runner = new PluginRunner(List.of(plugin));
            runner.onInvocationStart(new InvocationInfo("request", "arn:exec", true, Instant.EPOCH));
            runner.onInvocationEnd(new InvocationEndInfo("request", "arn:exec", true, InvocationStatus.SUCCEEDED, null));
            var spans = exporter.getFinishedSpanItems();
            if (spans.size() != 2 || spans.stream().anyMatch(s -> !s.getTraceId().equals("6955b900123456789012345678901234")))
                throw new AssertionError("legacy tracing failed: " + spans);
            type.getMethods(); // Public reflection must also work with an older core.
            checkOptionalHeaderDispatch(runner, plugin, exporter, type);
            System.out.println("PASS " + view + " core=" + DurableExecutionPlugin.class.getProtectionDomain()
                    .getCodeSource().getLocation() + " layer=" + path);
        } finally {
            if (old == null) System.clearProperty("com.amazonaws.xray.traceHeader");
            else System.setProperty("com.amazonaws.xray.traceHeader", old);
        }
    }
    private static void checkOptionalHeaderDispatch(PluginRunner runner, DurableExecutionPlugin plugin,
            InMemorySpanExporter exporter, Class<?> type) throws Exception {
        Method hook;
        try {
            hook = PluginRunner.class.getMethod("onInvocationStart", InvocationInfo.class, String.class);
        } catch (NoSuchMethodException olderCore) {
            return; // The older core retains the original hook and ordinary carriers.
        }
        boolean supportsHeader;
        try {
            type.getDeclaredMethod("onInvocationStart", InvocationInfo.class, String.class);
            supportsHeader = true;
        } catch (NoSuchMethodException olderPlugin) {
            supportsHeader = false;
        }
        exporter.reset();
        hook.invoke(runner, new InvocationInfo("request-2", "arn:exec", false, Instant.EPOCH),
                "Root=1-6955b900-aaaaaaaaaaaaaaaaaaaaaaaa;Parent=abcdefabcdefabcd;Sampled=0");
        runner.onInvocationEnd(new InvocationEndInfo("request-2", "arn:exec", false, InvocationStatus.SUCCEEDED, null));
        if (exporter.getFinishedSpanItems().size() != (supportsHeader ? 0 : 2))
            throw new AssertionError("Optional dispatch did not preserve new/legacy behavior");
    }
}
