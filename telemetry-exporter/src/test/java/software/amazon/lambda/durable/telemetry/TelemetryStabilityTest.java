// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TelemetryStabilityTest {

    @Test
    void compiledTelemetryClassesDoNotReferenceExperimentalAnnotation() throws Exception {
        var classes = Path.of(DurableExecutionTelemetry.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        assertTrue(Files.isDirectory(classes), "Expected the module's compiled classes directory");
        try (var paths = Files.walk(classes)) {
            var classFiles =
                    paths.filter(path -> path.toString().endsWith(".class")).toList();
            assertFalse(classFiles.isEmpty(), "Expected compiled telemetry classes, including nested types");
            for (var classFile : classFiles) {
                // Experimental has CLASS retention, so reflection cannot see it.
                // Its descriptor appears in the class file's constant pool when the annotation is present.
                var bytecode = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
                assertFalse(
                        bytecode.contains("Lsoftware/amazon/lambda/durable/annotations/Experimental;"),
                        () -> "Experimental annotation reference in " + classes.relativize(classFile));
            }
        }
    }
}
