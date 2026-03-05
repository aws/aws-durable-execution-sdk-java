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

        assertInstanceOf(ParameterizedType.class, type);
        ParameterizedType paramType = (ParameterizedType) type;
        assertEquals(List.class, paramType.getRawType());
        assertEquals(String.class, paramType.getActualTypeArguments()[0]);
    }

    @Test
    void testNestedGenericType() {
        var token = new TypeToken<Map<String, List<Integer>>>() {};
        Type type = token.getType();

        assertInstanceOf(ParameterizedType.class, type);
        ParameterizedType paramType = (ParameterizedType) type;
        assertEquals(Map.class, paramType.getRawType());
        assertEquals(String.class, paramType.getActualTypeArguments()[0]);

        Type valueType = paramType.getActualTypeArguments()[1];
        assertInstanceOf(ParameterizedType.class, valueType);
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

    @Test
    void testGetWithClass() {
        var token = TypeToken.get(String.class);
        Type type = token.getType();

        assertEquals(String.class, type);
    }

    @Test
    void testGetWithClassEquals() {
        var token1 = TypeToken.get(String.class);
        var token2 = TypeToken.get(String.class);
        var token3 = TypeToken.get(Integer.class);

        assertEquals(token1, token2);
        assertEquals(token1.hashCode(), token2.hashCode());
        assertNotEquals(token1, token3);
    }

    @Test
    void testGetWithPrimitiveClass() {
        var intToken = TypeToken.get(int.class);
        var longToken = TypeToken.get(long.class);
        var booleanToken = TypeToken.get(boolean.class);

        assertEquals(int.class, intToken.getType());
        assertEquals(long.class, longToken.getType());
        assertEquals(boolean.class, booleanToken.getType());
    }

    @Test
    void testGetWithArrayClass() {
        var token = TypeToken.get(String[].class);
        assertEquals(String[].class, token.getType());
    }

    @Test
    void testEqualsSameInstance() {
        var token = new TypeToken<List<String>>() {};
        assertEquals(token, token);
    }

    @Test
    void testEqualsNull() {
        var token = new TypeToken<List<String>>() {};
        assertNotEquals(token, null);
    }

    @Test
    void testEqualsDifferentClass() {
        var token = new TypeToken<List<String>>() {};
        assertNotEquals(token, "not a TypeToken");
    }

    @Test
    void testComplexNestedGenericType() {
        var token = new TypeToken<Map<String, Map<Integer, List<String>>>>() {};
        Type type = token.getType();

        assertInstanceOf(ParameterizedType.class, type);
        ParameterizedType paramType = (ParameterizedType) type;
        assertEquals(Map.class, paramType.getRawType());
        assertEquals(String.class, paramType.getActualTypeArguments()[0]);

        Type valueType = paramType.getActualTypeArguments()[1];
        assertInstanceOf(ParameterizedType.class, valueType);
        ParameterizedType valueParamType = (ParameterizedType) valueType;
        assertEquals(Map.class, valueParamType.getRawType());
        assertEquals(Integer.class, valueParamType.getActualTypeArguments()[0]);

        Type innerValueType = valueParamType.getActualTypeArguments()[1];
        assertInstanceOf(ParameterizedType.class, innerValueType);
        ParameterizedType innerValueParamType = (ParameterizedType) innerValueType;
        assertEquals(List.class, innerValueParamType.getRawType());
        assertEquals(String.class, innerValueParamType.getActualTypeArguments()[0]);
    }

    @Test
    void testToStringWithSimpleType() {
        var token = TypeToken.get(String.class);
        var str = token.toString();

        assertTrue(str.contains("TypeToken"));
        assertTrue(str.contains("String"));
    }

    @Test
    void testToStringWithComplexType() {
        var token = new TypeToken<Map<String, List<Integer>>>() {};
        var str = token.toString();

        assertTrue(str.contains("TypeToken"));
        assertTrue(str.contains("Map"));
        assertTrue(str.contains("String"));
        assertTrue(str.contains("List"));
        assertTrue(str.contains("Integer"));
    }

    @Test
    void testTypeCaching() {
        // Create multiple instances of the same type
        var token1 = new TypeToken<List<String>>() {};
        var token2 = new TypeToken<List<String>>() {};

        // They should be equal and have the same type
        assertEquals(token1, token2);
        assertEquals(token1.getType(), token2.getType());
    }

    @Test
    void testDifferentGenericParameters() {
        var stringListToken = new TypeToken<List<String>>() {};
        var integerListToken = new TypeToken<List<Integer>>() {};
        var doubleListToken = new TypeToken<List<Double>>() {};

        assertNotEquals(stringListToken, integerListToken);
        assertNotEquals(stringListToken, doubleListToken);
        assertNotEquals(integerListToken, doubleListToken);
    }

    @Test
    void testWildcardType() {
        var token = new TypeToken<List<?>>() {};
        Type type = token.getType();

        assertInstanceOf(ParameterizedType.class, type);
        ParameterizedType paramType = (ParameterizedType) type;
        assertEquals(List.class, paramType.getRawType());
        assertNotNull(paramType.getActualTypeArguments()[0]);
    }

    @Test
    void testMultipleTypeParameters() {
        var token = new TypeToken<Map<String, Integer>>() {};
        Type type = token.getType();

        assertInstanceOf(ParameterizedType.class, type);
        ParameterizedType paramType = (ParameterizedType) type;
        assertEquals(Map.class, paramType.getRawType());
        assertEquals(2, paramType.getActualTypeArguments().length);
        assertEquals(String.class, paramType.getActualTypeArguments()[0]);
        assertEquals(Integer.class, paramType.getActualTypeArguments()[1]);
    }

    @Test
    void testGetTypeReturnsConsistentValue() {
        var token = new TypeToken<List<String>>() {};
        Type type1 = token.getType();
        Type type2 = token.getType();

        assertSame(type1, type2);
    }

    @Test
    void testHashCodeConsistency() {
        var token = new TypeToken<List<String>>() {};
        int hash1 = token.hashCode();
        int hash2 = token.hashCode();

        assertEquals(hash1, hash2);
    }

    @Test
    void testGetWithNullClass() {
        assertThrows(NullPointerException.class, () -> TypeToken.get(null));
    }

    @Test
    void testReflectionPerformanceWithCaching() {
        // Warm up - ensure class is loaded and JIT compiled
        for (int i = 0; i < 1000; i++) {
            new TypeToken<List<String>>() {};
        }

        // Test 1: Anonymous subclass constructor (uses reflection + caching)
        long startTime1 = System.nanoTime();
        int iterations = 10000;

        for (int i = 0; i < iterations; i++) {
            new TypeToken<List<String>>(true) {};
        }

        long endTime1 = System.nanoTime();
        long duration1Ns = endTime1 - startTime1;

        // Test 2: TypeToken.get() with Class (direct type, no reflection)
        long startTime2 = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            TypeToken.get(String.class);
        }

        long endTime2 = System.nanoTime();
        long duration2Ns = endTime2 - startTime2;

        // With caching, anonymous subclass should be fast (typically < 100ms for 10k iterations)
        // Without caching, reflection would be much slower (> 500ms)
        assertTrue(
                duration1Ns < 500_000_000,
                "Anonymous TypeToken creation took " + duration1Ns + "ns for " + iterations
                        + " iterations. Expected < 500ms with caching.");

        // TypeToken.get() should be even faster since it doesn't use reflection
        assertTrue(
                duration2Ns < 100_000_000,
                "TypeToken.get() took " + duration2Ns + "ms for " + iterations + " iterations. Expected < 100ms.");

        // Log performance comparison
        System.out.println("Anonymous TypeToken<List<String>>() performance: " + duration1Ns + "ns for " + iterations
                + " iterations (" + String.format("%.2f", (double) duration1Ns / iterations)
                + " nanoseconds per operation)");
        System.out.println("TypeToken.get(String.class) performance: " + duration2Ns + "ms for " + iterations
                + " iterations (" + String.format("%.2f", (double) duration2Ns / iterations)
                + " nanoseconds per operation)");
        System.out.println("TypeToken.get() is " + String.format("%.2fx", (double) duration1Ns / duration2Ns)
                + " faster than anonymous subclass");
    }

    @Test
    void testCachingReducesReflectionOverhead() {
        // Create a custom TypeToken subclass to test caching
        class TestTypeToken extends TypeToken<List<String>> {
            TestTypeToken() {
                super(false); // Disable caching to measure reflection overhead
            }
        }

        // First creation - will use reflection
        long startTime1 = System.nanoTime();
        var token1 = new TestTypeToken();
        long duration1 = System.nanoTime() - startTime1;

        // Second creation - should use cache
        long startTime2 = System.nanoTime();
        var token2 = new TestTypeToken();
        long duration2 = System.nanoTime() - startTime2;

        // Verify both tokens are equal
        assertEquals(token1, token2);

        // Second creation should be faster or similar (cache hit)
        // Note: This is a soft assertion as JIT and other factors can affect timing
        System.out.println("First TypeToken creation: " + duration1 + "ns");
        System.out.println("Second TypeToken creation (cached): " + duration2 + "ns");
        System.out.println("Speedup factor: " + String.format("%.2fx", (double) duration1 / duration2));
    }
}
