// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TypeTokenTest {

    @Test
    void testSimpleGenericType() {
        var token = new TypeToken<List<String>>() {};
        Type type = token.getType();

        assertTrue(type instanceof ParameterizedType);
        ParameterizedType paramType = (ParameterizedType) type;
        assertEquals(List.class, paramType.getRawType());
        assertEquals(String.class, paramType.getActualTypeArguments()[0]);
    }

    @Test
    void testNestedGenericType() {
        var token = new TypeToken<Map<String, List<Integer>>>() {};
        Type type = token.getType();

        assertTrue(type instanceof ParameterizedType);
        ParameterizedType paramType = (ParameterizedType) type;
        assertEquals(Map.class, paramType.getRawType());
        assertEquals(String.class, paramType.getActualTypeArguments()[0]);

        Type valueType = paramType.getActualTypeArguments()[1];
        assertTrue(valueType instanceof ParameterizedType);
        ParameterizedType valueParamType = (ParameterizedType) valueType;
        assertEquals(List.class, valueParamType.getRawType());
        assertEquals(Integer.class, valueParamType.getActualTypeArguments()[0]);
    }

    @Test
    void testEqualsAndHashCode() {
        var token1 = new TypeToken<List<String>>() {};
        var token2 = new TypeToken<List<String>>() {};
        var token3 = new TypeToken<List<Integer>>() {};

        assertEquals(token1, token2);
        assertEquals(token1.hashCode(), token2.hashCode());
        assertNotEquals(token1, token3);
        assertNotEquals(token1.hashCode(), token3.hashCode());
    }

    @Test
    void testToString() {
        var token = new TypeToken<List<String>>() {};
        var str = token.toString();

        assertTrue(str.contains("TypeToken"));
        assertTrue(str.contains("List"));
        assertTrue(str.contains("String"));
    }

    @SuppressWarnings("rawtypes")
    @Test
    void testMissingTypeParameter() {
        assertThrows(IllegalStateException.class, () -> {
            // This should fail because no type parameter is provided
            new TypeToken() {};
        });
    }
}
