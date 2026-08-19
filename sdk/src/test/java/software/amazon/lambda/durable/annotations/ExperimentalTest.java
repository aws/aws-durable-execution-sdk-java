// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.annotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExperimentalTest {

    @Test
    void supportsPublicApisAndRecordComponents() {
        var targets = Set.of(Experimental.class.getAnnotation(Target.class).value());

        assertTrue(targets.contains(ElementType.TYPE));
        assertTrue(targets.contains(ElementType.METHOD));
        assertTrue(targets.contains(ElementType.FIELD));
        assertTrue(targets.contains(ElementType.PARAMETER));
        assertTrue(targets.contains(ElementType.RECORD_COMPONENT));
    }

    @Test
    void isDocumentedAndRetainedInClassFiles() {
        assertTrue(Experimental.class.isAnnotationPresent(Documented.class));
        assertEquals(
                RetentionPolicy.CLASS,
                Experimental.class.getAnnotation(Retention.class).value());
    }
}
