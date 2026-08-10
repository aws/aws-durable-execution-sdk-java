// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ExtensionOperationTest {
    @Test
    void exposesOnlyTheFullySpecifiedMethodForEachPrimitive() {
        var expected = Set.of(
                "createCallback(String,TypeToken,ExtensionCallbackConfig)",
                "invokeAsync(String,String,Object,TypeToken,ExtensionInvokeConfig)",
                "runInChildContextAsync(String,TypeToken,ExtensionContextFunction,ExtensionContextConfig)",
                "stepAsync(String,TypeToken,ExtensionStepFunction,ExtensionStepConfig)",
                "waitAsync(String,Duration)");

        var actual = Arrays.stream(ExtensionOperation.class.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .map(ExtensionOperationTest::signature)
                .collect(Collectors.toSet());

        assertEquals(expected, actual);
    }

    private static String signature(Method method) {
        var parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + ")";
    }
}
