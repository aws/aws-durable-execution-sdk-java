// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.lang.reflect.ParameterizedType;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.MapResult;

class MapOperationTypeTest {

    @Test
    void mapResultTypeHasValueEquality() {
        var first = MapOperation.mapResultType(TypeToken.get(String.class));
        var second = MapOperation.mapResultType(TypeToken.get(String.class));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertEquals(first.getType(), second.getType());
        assertEquals(first.getType().hashCode(), second.getType().hashCode());
        assertNotEquals(
                first.getType(),
                MapOperation.mapResultType(TypeToken.get(Integer.class)).getType());
    }

    @SuppressWarnings("unused")
    private MapResult<String> declaredMapResult;

    @Test
    void mapResultTypeFollowsParameterizedTypeContract() throws Exception {
        var jdkType = (ParameterizedType)
                MapOperationTypeTest.class.getDeclaredField("declaredMapResult").getGenericType();
        var ours = MapOperation.mapResultType(TypeToken.get(String.class)).getType();

        // equals must be symmetric with other ParameterizedType implementations, and hashCode must match theirs
        assertEquals(jdkType, ours);
        assertEquals(ours, jdkType);
        assertEquals(jdkType.hashCode(), ours.hashCode());
    }
}
