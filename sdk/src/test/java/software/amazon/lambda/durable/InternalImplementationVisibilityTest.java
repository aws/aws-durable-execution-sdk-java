// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.internal.InternalApi;
import software.amazon.lambda.durable.primitive.BasePrimitive;

class InternalImplementationVisibilityTest {
    @Test
    void primitivePackageIsMarkedInternal() {
        assertTrue(BasePrimitive.class.getPackage().isAnnotationPresent(InternalApi.class));
    }

    @Test
    void implementationTypesAreInternalOrPackagePrivate() throws ClassNotFoundException {
        assertTrue(Class.forName("software.amazon.lambda.durable.extension.ExtensionOperationImpl")
                .isAnnotationPresent(InternalApi.class));
        assertPackagePrivate("software.amazon.lambda.durable.primitive.SerializablePrimitive");
    }

    private void assertPackagePrivate(String className) throws ClassNotFoundException {
        var type = Class.forName(className);
        assertFalse(Modifier.isPublic(type.getModifiers()));
        assertFalse(Modifier.isProtected(type.getModifiers()));
        assertFalse(Modifier.isPrivate(type.getModifiers()));
    }
}
