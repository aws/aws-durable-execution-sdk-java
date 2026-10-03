// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.amazonaws.services.lambda.runtime.Context;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void fatalRuntimeFailuresStillPropagate() {
        var context = mock(Context.class);
        var fatal = new OutOfMemoryError("simulated");
        when(context.getXrayTraceId()).thenThrow(fatal);
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> RuntimeTraceHeader.capture(context)));
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
