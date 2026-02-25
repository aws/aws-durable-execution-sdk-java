// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;

class JacksonSerDesTypeTokenTest {

    private JacksonSerDes serDes;

    @BeforeEach
    void setUp() {
        serDes = new JacksonSerDes();
    }

    @Test
    void testDeserializeListOfStrings() {
        var json = "[\"apple\",\"banana\",\"cherry\"]";
        var token = new TypeToken<List<String>>() {};

        List<String> result = serDes.deserialize(json, token);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("apple", result.get(0));
        assertEquals("banana", result.get(1));
        assertEquals("cherry", result.get(2));
    }

    @Test
    void testDeserializeMapOfStringToInteger() {
        var json = "{\"one\":1,\"two\":2,\"three\":3}";
        var token = new TypeToken<Map<String, Integer>>() {};

        Map<String, Integer> result = serDes.deserialize(json, token);

        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals(1, result.get("one"));
        assertEquals(2, result.get("two"));
        assertEquals(3, result.get("three"));
    }

    @Test
    void testDeserializeNestedGeneric() {
        var json = "{\"items\":[\"a\",\"b\"],\"counts\":[1,2,3]}";
        var token = new TypeToken<Map<String, List<Object>>>() {};

        Map<String, List<Object>> result = serDes.deserialize(json, token);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.get("items") instanceof List);
        assertTrue(result.get("counts") instanceof List);
    }

    @Test
    void testDeserializeListOfMaps() {
        var json = "[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":25}]";
        var token = new TypeToken<List<Map<String, Object>>>() {};

        List<Map<String, Object>> result = serDes.deserialize(json, token);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("Alice", result.get(0).get("name"));
        assertEquals(30, result.get(0).get("age"));
        assertEquals("Bob", result.get(1).get("name"));
        assertEquals(25, result.get(1).get("age"));
    }

    @Test
    void testDeserializeNull() {
        var token = new TypeToken<List<String>>() {};
        List<String> result = serDes.deserialize(null, token);
        assertNull(result);
    }

    @Test
    void testDeserializeInvalidJson() {
        var json = "invalid json";
        var token = new TypeToken<List<String>>() {};

        assertThrows(SerDesException.class, () -> serDes.deserialize(json, token));
    }

    @Test
    void testTypeCaching() {
        // Deserialize multiple times with same TypeToken to verify caching works correctly
        var json1 = "[1,2,3]";
        var json2 = "[4,5,6]";
        var token = new TypeToken<List<Integer>>() {};

        // First deserialization - should populate cache
        List<Integer> result1 = serDes.deserialize(json1, token);
        assertNotNull(result1);
        assertEquals(3, result1.size());
        assertEquals(1, result1.get(0));

        // Second deserialization with same TypeToken - should use cached JavaType
        List<Integer> result2 = serDes.deserialize(json2, token);
        assertNotNull(result2);
        assertEquals(3, result2.size());
        assertEquals(4, result2.get(0));

        // Third deserialization with different data but same TypeToken
        var json3 = "[7,8,9,10]";
        List<Integer> result3 = serDes.deserialize(json3, token);
        assertNotNull(result3);
        assertEquals(4, result3.size());
        assertEquals(7, result3.get(0));

        // Verify all results are different instances but correctly deserialized
        assertNotEquals(result1, result2);
        assertNotEquals(result2, result3);
    }

    @Test
    void testTypeCachingWithDifferentTypeTokens() {
        // Test that different TypeTokens with same generic type work correctly
        var jsonInts = "[1,2,3]";
        var jsonStrings = "[\"a\",\"b\",\"c\"]";

        var intToken = new TypeToken<List<Integer>>() {};
        var stringToken = new TypeToken<List<String>>() {};

        // Deserialize with integer token
        List<Integer> intResult = serDes.deserialize(jsonInts, intToken);
        assertNotNull(intResult);
        assertEquals(3, intResult.size());
        assertEquals(1, intResult.get(0));

        // Deserialize with string token - should use different cached type
        List<String> stringResult = serDes.deserialize(jsonStrings, stringToken);
        assertNotNull(stringResult);
        assertEquals(3, stringResult.size());
        assertEquals("a", stringResult.get(0));

        // Reuse integer token - should use cached type
        var jsonInts2 = "[10,20,30]";
        List<Integer> intResult2 = serDes.deserialize(jsonInts2, intToken);
        assertNotNull(intResult2);
        assertEquals(3, intResult2.size());
        assertEquals(10, intResult2.get(0));
    }

    @Test
    void testSerializeAndDeserializeRoundTrip() {
        var original = List.of("one", "two", "three");
        var json = serDes.serialize(original);
        var token = new TypeToken<List<String>>() {};

        List<String> result = serDes.deserialize(json, token);

        assertEquals(original, result);
    }

    static class TestObject {
        public String name;
        public int value;

        public TestObject() {}

        public TestObject(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }

    @Test
    void testDeserializeListOfCustomObjects() {
        var json = "[{\"name\":\"test1\",\"value\":10},{\"name\":\"test2\",\"value\":20}]";
        var token = new TypeToken<List<TestObject>>() {};

        List<TestObject> result = serDes.deserialize(json, token);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("test1", result.get(0).name);
        assertEquals(10, result.get(0).value);
        assertEquals("test2", result.get(1).name);
        assertEquals(20, result.get(1).value);
    }
}
