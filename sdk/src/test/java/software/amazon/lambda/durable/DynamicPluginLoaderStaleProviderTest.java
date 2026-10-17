// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
import software.amazon.lambda.durable.plugin.InvocationInfo;

/**
 * Covers the startup check that rejects a plugin provider compiled against the older provider interface.
 *
 * <p>The condition being checked is a property of a class file, not of source: the provider's class file declares that
 * it implements {@code DurableExecutionPluginProvider} but contains no {@code createPlugin(InvocationInfo)} method.
 * That class file cannot be produced from this source tree, because this source tree contains only the current
 * interface and a class that fails to implement one of its abstract methods does not compile. Each test here therefore
 * compiles a provider against a stub of the older interface and then loads the result against the current interface,
 * which is the deployment it stands in for: a provider JAR built against an earlier SDK and left in place while the
 * function's SDK dependency was raised.
 *
 * <p>The fixture reproduces the condition rather than approximating it, so these tests establish that the check fires
 * on a class file with the shape a stale provider JAR has. A cheaper fixture would not: a
 * {@link java.lang.reflect.Proxy} over the provider interface generates a concrete
 * {@code createPlugin(InvocationInfo)}, so it does not reproduce the condition at all.
 *
 * <p>What these tests do not establish is that a provider JAR built by some other toolchain against some other 2.x
 * point release produces exactly this class file shape. They cover one stale shape, the one the migration guide
 * describes.
 */
class DynamicPluginLoaderStaleProviderTest {

    private static final String PROVIDER_CLASS = "com.example.audit.StaleAuditProvider";
    private static final String PROVIDER_NAME = "com.example.audit";

    /** The plugin interface, which is unchanged, so the stale provider's references to it still resolve. */
    private static final String PLUGIN_SOURCE = """
            package software.amazon.lambda.durable.plugin;

            public interface DurableExecutionPlugin {}
            """;

    /** The provider interface as an earlier SDK declared it, against which the fixture provider is compiled. */
    private static final String OLD_PROVIDER_INTERFACE_SOURCE = """
            package software.amazon.lambda.durable.plugin;

            public interface DurableExecutionPluginProvider {

                int API_VERSION = 1;

                String getName();

                int getApiVersion();

                Class<? extends DurableExecutionPlugin> getPluginType();

                DurableExecutionPlugin createPlugin();
            }
            """;

    /**
     * The invocation info as a stub, so a fixture can name it in a signature.
     *
     * <p>Not handed to the loader, so a compiled reference to it resolves to this SDK's class at load time and the
     * fixture's method descriptor matches the one {@code getMethod} is asked for.
     */
    private static final String INVOCATION_INFO_SOURCE = """
            package software.amazon.lambda.durable.plugin;

            public final class InvocationInfo {}
            """;

    /** A provider whose only createPlugin(InvocationInfo) is static, which cannot implement an instance method. */
    private static final String STATIC_PROVIDER_SOURCE = """
            package com.example.audit;

            import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
            import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
            import software.amazon.lambda.durable.plugin.InvocationInfo;

            public final class StaleAuditProvider implements DurableExecutionPluginProvider {

                @Override
                public String getName() {
                    return "com.example.audit";
                }

                @Override
                public int getApiVersion() {
                    return API_VERSION;
                }

                @Override
                public Class<? extends DurableExecutionPlugin> getPluginType() {
                    return StaleAuditPlugin.class;
                }

                @Override
                public DurableExecutionPlugin createPlugin() {
                    return new StaleAuditPlugin();
                }

                public static DurableExecutionPlugin createPlugin(InvocationInfo info) {
                    return new StaleAuditPlugin();
                }

                public static final class StaleAuditPlugin implements DurableExecutionPlugin {}
            }
            """;

    /**
     * A provider whose createPlugin(InvocationInfo) returns a plugin subtype and overrides nothing.
     *
     * <p>Compiled against the older interface, so the method overrides no abstract declaration and javac emits no
     * bridge returning {@code DurableExecutionPlugin}. The return type is still a plugin, so a check that asked only
     * whether the return type were assignable to {@code DurableExecutionPlugin} would accept it, while
     * {@code invokeinterface} looks for the interface's erased descriptor and finds none.
     */
    private static final String SUBTYPE_RETURN_PROVIDER_SOURCE = """
            package com.example.audit;

            import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
            import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
            import software.amazon.lambda.durable.plugin.InvocationInfo;

            public final class StaleAuditProvider implements DurableExecutionPluginProvider {

                @Override
                public String getName() {
                    return "com.example.audit";
                }

                @Override
                public int getApiVersion() {
                    return API_VERSION;
                }

                @Override
                public Class<? extends DurableExecutionPlugin> getPluginType() {
                    return StaleAuditPlugin.class;
                }

                @Override
                public DurableExecutionPlugin createPlugin() {
                    return new StaleAuditPlugin();
                }

                public StaleAuditPlugin createPlugin(InvocationInfo info) {
                    return new StaleAuditPlugin();
                }

                public static final class StaleAuditPlugin implements DurableExecutionPlugin {}
            }
            """;

    /** A provider whose createPlugin(InvocationInfo) returns something that is not a plugin. */
    private static final String WRONG_RETURN_PROVIDER_SOURCE = """
            package com.example.audit;

            import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
            import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;
            import software.amazon.lambda.durable.plugin.InvocationInfo;

            public final class StaleAuditProvider implements DurableExecutionPluginProvider {

                @Override
                public String getName() {
                    return "com.example.audit";
                }

                @Override
                public int getApiVersion() {
                    return API_VERSION;
                }

                @Override
                public Class<? extends DurableExecutionPlugin> getPluginType() {
                    return StaleAuditPlugin.class;
                }

                @Override
                public DurableExecutionPlugin createPlugin() {
                    return new StaleAuditPlugin();
                }

                public String createPlugin(InvocationInfo info) {
                    return "not a plugin";
                }

                public static final class StaleAuditPlugin implements DurableExecutionPlugin {}
            }
            """;

    /** A provider written against the interface above, exactly as the migration guide's "before" example is. */
    private static final String PROVIDER_SOURCE = """
            package com.example.audit;

            import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
            import software.amazon.lambda.durable.plugin.DurableExecutionPluginProvider;

            public final class StaleAuditProvider implements DurableExecutionPluginProvider {

                @Override
                public String getName() {
                    return "com.example.audit";
                }

                @Override
                public int getApiVersion() {
                    return API_VERSION;
                }

                @Override
                public Class<? extends DurableExecutionPlugin> getPluginType() {
                    return StaleAuditPlugin.class;
                }

                @Override
                public DurableExecutionPlugin createPlugin() {
                    return new StaleAuditPlugin();
                }

                public static final class StaleAuditPlugin implements DurableExecutionPlugin {}
            }
            """;

    @Test
    void staleProviderIsIndistinguishableFromACurrentOneUntilCreatePluginIsResolved(@TempDir Path workDir)
            throws Exception {
        var provider = staleProvider(workDir);

        // Nothing the provider's class file references was removed, so it loads, instantiates, and reports its name.
        assertEquals(PROVIDER_NAME, provider.getName());

        // Its createPlugin(InvocationInfo) resolves to the abstract declaration on the interface it inherits.
        var createPlugin = provider.getClass().getMethod("createPlugin", InvocationInfo.class);
        assertTrue(Modifier.isAbstract(createPlugin.getModifiers()));
        assertTrue(createPlugin.getDeclaringClass().isInterface());

        // Calling it fails, which is the outcome the startup check exists to reach first.
        assertThrows(AbstractMethodError.class, () -> provider.createPlugin(invocationInfo()));
    }

    @Test
    void rejectsSelectedStaleProviderAtConfigurationTime(@TempDir Path workDir) throws Exception {
        var provider = staleProvider(workDir);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories(PROVIDER_NAME, List.of(provider), List.of()));

        var message = error.getMessage();
        assertTrue(message.contains("Dynamic plugin configuration failed"), message);
        assertTrue(message.contains("Plugin provider '" + PROVIDER_NAME + "'"), message);
        assertTrue(message.contains(PROVIDER_CLASS), message);
        assertTrue(message.contains("does not implement createPlugin(InvocationInfo)"), message);
        assertTrue(message.contains("compiled against an older Durable Execution SDK"), message);
        assertTrue(message.contains("Rebuild the provider against this SDK version and redeploy it"), message);
        assertTrue(message.contains("Lambda layer is versioned and deployed separately"), message);

        // The artifact to rebuild is named, because an operator with several provider layers deployed needs to know
        // which one is stale.
        var artifactLocation =
                provider.getClass().getProtectionDomain().getCodeSource().getLocation();
        assertTrue(message.contains(artifactLocation.toString()), message);
    }

    @Test
    void rejectsAProviderWhoseCreatePluginIsStatic(@TempDir Path workDir) throws Exception {
        // getMethod searches the class before the interfaces it implements and returns static methods, so a stale class
        // carrying a static createPlugin(InvocationInfo) helper resolves to that helper. The instance method the
        // interface call dispatches to is still missing, so the absence of the abstract modifier proves nothing here.
        var provider = staleProviderOfShape(workDir, STATIC_PROVIDER_SOURCE);

        var createPlugin = provider.getClass().getMethod("createPlugin", InvocationInfo.class);
        assertTrue(Modifier.isStatic(createPlugin.getModifiers()));
        assertTrue(!Modifier.isAbstract(createPlugin.getModifiers()));
        assertThrows(AbstractMethodError.class, () -> provider.createPlugin(invocationInfo()));

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories(PROVIDER_NAME, List.of(provider), List.of()));

        var message = error.getMessage();
        assertTrue(message.contains("does not implement createPlugin(InvocationInfo)"), message);
        assertTrue(message.contains("is static"), message);
        assertTrue(message.contains("Rebuild the provider against this SDK version and redeploy it"), message);
    }

    @Test
    void rejectsAProviderWhoseCreatePluginReturnsSomethingElse(@TempDir Path workDir) throws Exception {
        // A createPlugin(InvocationInfo) whose return type is unrelated to DurableExecutionPlugin does not override the
        // interface method, so it is concrete and still leaves the interface call unimplemented.
        var provider = staleProviderOfShape(workDir, WRONG_RETURN_PROVIDER_SOURCE);

        var createPlugin = provider.getClass().getMethod("createPlugin", InvocationInfo.class);
        assertEquals(String.class, createPlugin.getReturnType());
        assertTrue(!Modifier.isAbstract(createPlugin.getModifiers()));
        assertThrows(AbstractMethodError.class, () -> provider.createPlugin(invocationInfo()));

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories(PROVIDER_NAME, List.of(provider), List.of()));

        var message = error.getMessage();
        assertTrue(message.contains("does not implement createPlugin(InvocationInfo)"), message);
        assertTrue(message.contains("returns java.lang.String"), message);
    }

    @Test
    void rejectsAProviderWhoseCreatePluginHasNoBridge(@TempDir Path workDir) throws Exception {
        // The shape an assignability test accepts and the JVM does not. The method is concrete, takes this SDK's
        // InvocationInfo, and returns a DurableExecutionPlugin subtype, but it overrides nothing, so there is no bridge
        // carrying the interface's erased descriptor and invokeinterface finds nothing to dispatch to.
        var provider = staleProviderOfShape(workDir, SUBTYPE_RETURN_PROVIDER_SOURCE);

        var resolved = provider.getClass().getMethod("createPlugin", InvocationInfo.class);
        assertTrue(!Modifier.isAbstract(resolved.getModifiers()));
        assertTrue(!Modifier.isStatic(resolved.getModifiers()));
        assertTrue(
                DurableExecutionPlugin.class.isAssignableFrom(resolved.getReturnType()),
                "the fixture is only interesting while an assignability test would accept it");
        assertTrue(
                Stream.of(provider.getClass().getMethods())
                        .noneMatch(method -> "createPlugin".equals(method.getName())
                                && method.getParameterCount() == 1
                                && method.getParameterTypes()[0] == InvocationInfo.class
                                && method.getReturnType() == DurableExecutionPlugin.class
                                && !Modifier.isAbstract(method.getModifiers())),
                "the fixture must carry no bridge method, which is what makes the call fail");
        assertThrows(AbstractMethodError.class, () -> provider.createPlugin(invocationInfo()));

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories(PROVIDER_NAME, List.of(provider), List.of()));

        var message = error.getMessage();
        assertTrue(message.contains("does not implement createPlugin(InvocationInfo)"), message);
        assertTrue(message.contains("carries no method returning"), message);
    }

    @Test
    void acceptsAProviderWhoseCreatePluginReturnsASubtype() {
        // The same covariant return, compiled against the current interface: javac emits the bridge, the interface call
        // dispatches, and the check must accept it. This is what keeps the exact-descriptor rule from rejecting a
        // provider that works.
        var provider = new CovariantProvider();

        var factories = DynamicPluginLoader.loadConfiguredPluginFactories("covariant", List.of(provider), List.of());

        assertEquals(List.of(provider), factories);
        assertTrue(provider.createPlugin(invocationInfo()) instanceof CurrentPlugin);
    }

    @Test
    void doesNotRejectAStaleProviderThatWasNotSelected(@TempDir Path workDir) throws Exception {
        var staleProvider = staleProvider(workDir);
        var selectedProvider = new CurrentProvider();

        // A stale provider JAR on the class path that no name in the environment variable selects is never called, so
        // rejecting it would fail startup for a deployment that works. Only selected providers are checked.
        var factories = DynamicPluginLoader.loadConfiguredPluginFactories(
                "current", List.of(staleProvider, selectedProvider), List.of());

        assertEquals(List.of(selectedProvider), factories);
    }

    @Test
    void namesTheProviderClassWhenNoArtifactLocationIsReported(@TempDir Path workDir) throws Exception {
        // A class whose loader reports no code source has no artifact to name. That says nothing about whether the
        // provider is usable, so the failure is still reported and only the location is left out of the message.
        var provider = staleProvider(workDir, null);

        var error = assertThrows(
                IllegalStateException.class,
                () -> DynamicPluginLoader.loadConfiguredPluginFactories(PROVIDER_NAME, List.of(provider), List.of()));

        var message = error.getMessage();
        assertTrue(message.contains("(" + PROVIDER_CLASS + ")"), message);
        assertTrue(message.contains("does not implement createPlugin(InvocationInfo)"), message);
    }

    private static InvocationInfo invocationInfo() {
        return new InvocationInfo("req-123", "arn:test", true, Instant.now());
    }

    /**
     * Compiles the fixture provider against the older interface and returns an instance of it loaded against the
     * current interface.
     *
     * <p>The stub interfaces are compiled only so the provider source has something to compile against, and they are
     * not handed to the loader. Class loading for every {@code software.amazon.lambda.durable} name therefore reaches
     * the parent loader and resolves to this SDK's classes, which is the same resolution a deployed provider JAR gets
     * from the function class path.
     */
    private static DurableExecutionPluginProvider staleProvider(Path workDir) throws Exception {
        return staleProviderOfShape(workDir, PROVIDER_SOURCE);
    }

    /** @param providerSource the stale shape to compile, one of the provider sources above */
    private static DurableExecutionPluginProvider staleProviderOfShape(Path workDir, String providerSource)
            throws Exception {
        return staleProvider(workDir, workDir.resolve("classes").toUri().toURL(), providerSource);
    }

    /** @param artifactLocation reported as the fixture classes' code source, or null to report none */
    private static DurableExecutionPluginProvider staleProvider(Path workDir, URL artifactLocation) throws Exception {
        return staleProvider(workDir, artifactLocation, PROVIDER_SOURCE);
    }

    /** @param artifactLocation reported as the fixture classes' code source, or null to report none */
    private static DurableExecutionPluginProvider staleProvider(
            Path workDir, URL artifactLocation, String providerSource) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assumeTrue(compiler != null, "This test compiles a fixture and needs a JDK rather than a JRE");

        var classDir = compileFixture(compiler, workDir, providerSource);
        var loader = new FixtureClassLoader(
                DynamicPluginLoaderStaleProviderTest.class.getClassLoader(),
                fixtureClasses(classDir),
                artifactLocation);
        var type = loader.loadClass(PROVIDER_CLASS);
        return (DurableExecutionPluginProvider) type.getDeclaredConstructor().newInstance();
    }

    private static Path compileFixture(JavaCompiler compiler, Path workDir, String providerSource) throws Exception {
        var sourceDir = Files.createDirectories(workDir.resolve("source"));
        var classDir = Files.createDirectories(workDir.resolve("classes"));
        var sources = new String[] {
            write(sourceDir, "DurableExecutionPlugin.java", PLUGIN_SOURCE),
            write(sourceDir, "DurableExecutionPluginProvider.java", OLD_PROVIDER_INTERFACE_SOURCE),
            write(sourceDir, "InvocationInfo.java", INVOCATION_INFO_SOURCE),
            write(sourceDir, "StaleAuditProvider.java", providerSource),
        };

        // The class path holds only the output directory, which is empty when the compile starts. This SDK's current
        // interfaces are therefore not visible to the compile, and the provider is compiled against the stub above
        // rather than against the interface it is meant to predate.
        var arguments = Stream.concat(
                        Stream.of("--release", "17", "-classpath", classDir.toString(), "-d", classDir.toString()),
                        Stream.of(sources))
                .toArray(String[]::new);
        var diagnostics = new ByteArrayOutputStream();
        var exitCode = compiler.run(null, null, diagnostics, arguments);
        if (exitCode != 0) {
            fail("Failed to compile the stale provider fixture: " + diagnostics.toString(StandardCharsets.UTF_8));
        }
        return classDir;
    }

    /**
     * Returns the compiled fixture classes outside the {@code software.amazon.lambda.durable} packages, keyed by binary
     * name.
     *
     * <p>Excluding those packages is what leaves the stub interfaces behind. A stub that reached the loader would
     * shadow this SDK's interface of the same name, and the provider would then implement the stub rather than the
     * current interface, which is not the condition under test.
     */
    private static Map<String, byte[]> fixtureClasses(Path classDir) throws Exception {
        var classes = new HashMap<String, byte[]>();
        try (var files = Files.walk(classDir)) {
            for (var file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                var relativePath = classDir.relativize(file).toString();
                var binaryName = relativePath
                        .substring(0, relativePath.length() - ".class".length())
                        .replace(File.separatorChar, '.');
                if (!binaryName.startsWith("software.amazon.lambda.durable.")) {
                    classes.put(binaryName, Files.readAllBytes(file));
                }
            }
        }
        return classes;
    }

    private static String write(Path sourceDir, String fileName, String source) throws Exception {
        var file = sourceDir.resolve(fileName);
        Files.writeString(file, source);
        return file.toString();
    }

    /** Defines the fixture classes and delegates every other name to the parent loader. */
    private static final class FixtureClassLoader extends ClassLoader {

        private final Map<String, byte[]> fixtureClasses;
        private final ProtectionDomain protectionDomain;

        /**
         * @param artifactLocation where the fixture classes were loaded from, reported as their code source so the
         *     failure message can name it as it names a deployed provider's JAR, or null to report no code source
         */
        FixtureClassLoader(ClassLoader parent, Map<String, byte[]> fixtureClasses, URL artifactLocation) {
            super(parent);
            this.fixtureClasses = Map.copyOf(fixtureClasses);
            this.protectionDomain = artifactLocation == null
                    ? null
                    : new ProtectionDomain(new CodeSource(artifactLocation, (CodeSigner[]) null), null);
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            var bytes = fixtureClasses.get(name);
            if (bytes == null) {
                return super.findClass(name);
            }
            return defineClass(name, bytes, 0, bytes.length, protectionDomain);
        }
    }

    /** A provider written against the current interface, used to show that only selected providers are checked. */
    private static final class CurrentProvider implements DurableExecutionPluginProvider {

        @Override
        public String getName() {
            return "current";
        }

        @Override
        public DurableExecutionPlugin createPlugin(InvocationInfo invocationInfo) {
            return new CurrentPlugin();
        }
    }

    /** A provider written against the current interface with a covariant return, so javac emits a bridge. */
    private static final class CovariantProvider implements DurableExecutionPluginProvider {

        @Override
        public String getName() {
            return "covariant";
        }

        @Override
        public CurrentPlugin createPlugin(InvocationInfo invocationInfo) {
            return new CurrentPlugin();
        }
    }

    private static final class CurrentPlugin implements DurableExecutionPlugin {}
}
