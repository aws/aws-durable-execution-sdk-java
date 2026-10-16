// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.lambda.durable.util.ExceptionHelper;

class RuntimeTraceHeaderTest {
    @Test
    void missingAccessorOnOlderVisibleLambdaApiRetainsFallback(@TempDir Path directory) throws Exception {
        var source = directory.resolve("Context.java");
        Files.writeString(source, "package com.amazonaws.services.lambda.runtime; public interface Context {} ");
        var errors = new ByteArrayOutputStream();
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, errors, "--release", "17", "-d", directory.toString(), source.toString()),
                errors.toString(StandardCharsets.UTF_8));
        try (var loader = oldRuntimeLoader(directory)) {
            var contextType = loader.loadClass(Context.class.getName());
            var context = Proxy.newProxyInstance(loader, new Class<?>[] {contextType}, (proxy, method, args) -> {
                throw new AssertionError("No accessor should be invoked");
            });
            var helper = loader.loadClass(RuntimeTraceHeader.class.getName());
            var capture = helper.getDeclaredMethod("capture", contextType);
            capture.setAccessible(true);
            assertNull(capture.invoke(null, context));
        }
    }

    @Test
    void preAccessorContextBinaryRetainsFallbackWithNewDefaultInterface(@TempDir Path directory) throws Exception {
        var oldApi = Files.createDirectories(directory.resolve("old-api"));
        var classes = Files.createDirectories(directory.resolve("classes"));
        var apiSource = directory.resolve("Context.java");
        Files.writeString(apiSource, """
                package com.amazonaws.services.lambda.runtime;
                public interface Context {
                    String getAwsRequestId(); String getLogGroupName(); String getLogStreamName();
                    String getFunctionName(); String getFunctionVersion(); String getInvokedFunctionArn();
                    CognitoIdentity getIdentity(); ClientContext getClientContext();
                    int getRemainingTimeInMillis(); int getMemoryLimitInMB(); LambdaLogger getLogger();
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var classpath = System.getProperty("java.class.path");
        var errors = new ByteArrayOutputStream();
        assertEquals(
                0,
                compiler.run(
                        null,
                        null,
                        errors,
                        "--release",
                        "17",
                        "-cp",
                        classpath,
                        "-d",
                        oldApi.toString(),
                        apiSource.toString()),
                errors.toString(StandardCharsets.UTF_8));
        var legacySource = directory.resolve("LegacyContext.java");
        Files.writeString(legacySource, """
                import com.amazonaws.services.lambda.runtime.*;
                public class LegacyContext implements Context {
                    public String getAwsRequestId() { return "legacy"; }
                    public String getLogGroupName() { return null; }
                    public String getLogStreamName() { return null; }
                    public String getFunctionName() { return null; }
                    public String getFunctionVersion() { return null; }
                    public String getInvokedFunctionArn() { return null; }
                    public CognitoIdentity getIdentity() { return null; }
                    public ClientContext getClientContext() { return null; }
                    public int getRemainingTimeInMillis() { return 30000; }
                    public int getMemoryLimitInMB() { return 128; }
                    public LambdaLogger getLogger() { return null; }
                }
                """);
        assertEquals(
                0,
                compiler.run(
                        null,
                        null,
                        errors,
                        "--release",
                        "17",
                        "-cp",
                        oldApi + File.pathSeparator + classpath,
                        "-d",
                        classes.toString(),
                        legacySource.toString()),
                errors.toString(StandardCharsets.UTF_8));
        var overrideSource = directory.resolve("ExplicitNullContext.java");
        Files.writeString(overrideSource, """
                public class ExplicitNullContext extends LegacyContext {
                    @Override public String getXrayTraceId() { return null; }
                }
                """);
        var inheritedSource = directory.resolve("InheritedNullContext.java");
        Files.writeString(inheritedSource, "public class InheritedNullContext extends ExplicitNullContext {}");
        assertEquals(
                0,
                compiler.run(
                        null,
                        null,
                        errors,
                        "--release",
                        "17",
                        "-cp",
                        classes + File.pathSeparator + classpath,
                        "-d",
                        classes.toString(),
                        overrideSource.toString(),
                        inheritedSource.toString()),
                errors.toString(StandardCharsets.UTF_8));
        try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, Context.class.getClassLoader())) {
            var legacy =
                    (Context) loader.loadClass("LegacyContext").getConstructor().newInstance();
            assertEquals(
                    Context.class, legacy.getClass().getMethod("getXrayTraceId").getDeclaringClass());
            assertNull(legacy.getXrayTraceId(), "the new interface default returns null");
            assertNull(RuntimeTraceHeader.capture(legacy), "inherited interface default is not a runtime carrier");
            var explicit = (Context)
                    loader.loadClass("ExplicitNullContext").getConstructor().newInstance();
            assertEquals(
                    "",
                    RuntimeTraceHeader.capture(explicit),
                    "actual runtime override is authoritative even when null");
            var inherited = (Context)
                    loader.loadClass("InheritedNullContext").getConstructor().newInstance();
            assertEquals("", RuntimeTraceHeader.capture(inherited), "an inherited runtime override remains available");
        }
    }

    @Test
    void availableAccessorWithNoHeaderIsDistinctFromUnavailableApi() {
        var context = mock(RuntimeContext.class);
        when(context.getXrayTraceId()).thenReturn(null, "");
        assertEquals("", RuntimeTraceHeader.capture(context));
        assertEquals("", RuntimeTraceHeader.capture(context));
        assertNull(RuntimeTraceHeader.capture(null), "no runtime snapshot retains legacy fallback");
    }

    @Test
    void runtimeAccessorFailuresAreAuthoritativeAbsence() {
        var context = mock(RuntimeContext.class);
        when(context.getXrayTraceId())
                .thenThrow(new SecurityException("access denied"), new IllegalStateException("carrier unavailable"));
        assertEquals("", RuntimeTraceHeader.capture(context));
        assertEquals("", RuntimeTraceHeader.capture(context));
    }

    @Test
    void fatalRuntimeFailuresStillPropagate() {
        var context = mock(RuntimeContext.class);
        var fatal = new OutOfMemoryError("simulated");
        when(context.getXrayTraceId()).thenThrow(fatal);
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> RuntimeTraceHeader.capture(context)));
    }

    @ParameterizedTest
    @MethodSource("nonfatalAccessorFailures")
    void nonfatalErrorsFromAvailableOverrideAreAuthoritativeAbsence(Throwable failure) {
        var context = mock(RuntimeContext.class);
        when(context.getXrayTraceId()).thenAnswer(invocation -> {
            throw failure;
        });
        assertEquals("", RuntimeTraceHeader.capture(context));
        verify(context, times(1)).getXrayTraceId();
    }

    private static Stream<Throwable> nonfatalAccessorFailures() {
        return Stream.of(
                new AssertionError("optional accessor assertion"),
                new NoClassDefFoundError("optional carrier dependency"),
                new NoSuchMethodError("method inside the runtime override"),
                new AbstractMethodError("implementation inside the runtime override"),
                new ExceptionInInitializerError(new IllegalStateException("optional dependency init")),
                new CompletionException(new AssertionError("wrapped optional assertion")));
    }

    @ParameterizedTest
    @MethodSource("fatalAccessorFailures")
    void directAndWrappedFatalAccessorFailuresRetainIdentity(Throwable failure, Error fatal) {
        var context = mock(RuntimeContext.class);
        when(context.getXrayTraceId()).thenAnswer(invocation -> {
            throw failure;
        });
        assertSame(fatal, assertThrows(Error.class, () -> RuntimeTraceHeader.capture(context)));
        verify(context, times(1)).getXrayTraceId();
    }

    @SuppressWarnings("removal")
    private static Stream<Arguments> fatalAccessorFailures() {
        return Stream.<Error>of(new InternalError("simulated VM fatal"), new ThreadDeath())
                .flatMap(fatal -> Stream.of(
                        Arguments.of(fatal, fatal),
                        Arguments.of(new CompletionException(fatal), fatal),
                        Arguments.of(new ExecutionException(fatal), fatal),
                        Arguments.of(new InvocationTargetException(fatal), fatal),
                        Arguments.of(new UndeclaredThrowableException(fatal), fatal),
                        Arguments.of(new CompletionException(new InvocationTargetException(fatal)), fatal)));
    }

    @ParameterizedTest
    @MethodSource("unreadableCauses")
    void unreadableNonfatalWrapperCauseIsAuthoritativeAbsence(Throwable unreadable) {
        var reads = new AtomicInteger();
        var wrapper = new CompletionException("unreadable", null) {
            @Override
            public synchronized Throwable getCause() {
                reads.incrementAndGet();
                ExceptionHelper.sneakyThrow(unreadable);
                return null;
            }
        };
        var context = mock(RuntimeContext.class);
        when(context.getXrayTraceId()).thenThrow(wrapper);
        assertEquals("", RuntimeTraceHeader.capture(context));
        assertEquals(1, reads.get());
    }

    private static Stream<Throwable> unreadableCauses() {
        return Stream.of(new IllegalStateException("unreadable"), new AssertionError("unreadable"));
    }

    @Test
    void wrapperCauseIsReadOnceAndFatalIdentityIsPreserved() {
        var reads = new AtomicInteger();
        var fatal = new InternalError("original cause");
        var wrapper = new CompletionException("changing cause", null) {
            @Override
            public synchronized Throwable getCause() {
                if (reads.incrementAndGet() > 1) throw new IllegalStateException("second cause read");
                return fatal;
            }
        };
        var context = mock(RuntimeContext.class);
        when(context.getXrayTraceId()).thenThrow(wrapper);
        assertSame(fatal, assertThrows(InternalError.class, () -> RuntimeTraceHeader.capture(context)));
        assertEquals(1, reads.get());
    }

    @Test
    void fatalCauseAccessorFailureRetainsIdentity() {
        var fatal = new InternalError("cause accessor fatal");
        var wrapper = new CompletionException("unreadable", null) {
            @Override
            public synchronized Throwable getCause() {
                throw fatal;
            }
        };
        var context = mock(RuntimeContext.class);
        when(context.getXrayTraceId()).thenThrow(wrapper);
        assertSame(fatal, assertThrows(InternalError.class, () -> RuntimeTraceHeader.capture(context)));
    }

    private abstract static class RuntimeContext implements Context {
        @Override
        public String getXrayTraceId() {
            return null;
        }
    }

    private static URLClassLoader oldRuntimeLoader(Path directory) throws Exception {
        byte[] helper;
        try (var stream = RuntimeTraceHeader.class.getResourceAsStream("RuntimeTraceHeader.class")) {
            helper = stream.readAllBytes();
        }
        return new URLClassLoader(new URL[] {directory.toUri().toURL()}, RuntimeTraceHeader.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(Context.class.getName()) && !name.equals(RuntimeTraceHeader.class.getName())) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    var type = findLoadedClass(name);
                    if (type == null)
                        type = name.equals(Context.class.getName())
                                ? findClass(name)
                                : defineClass(name, helper, 0, helper.length);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        };
    }
}
