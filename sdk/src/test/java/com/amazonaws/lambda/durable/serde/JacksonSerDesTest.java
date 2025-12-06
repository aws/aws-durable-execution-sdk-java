package com.amazonaws.lambda.durable.serde;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JacksonSerDesTest {
    
    record TestData(String name, int value) {}
    
    @Test
    void testRoundTrip() {
        var serDes = new JacksonSerDes();
        var original = new TestData("test", 42);
        
        var json = serDes.serialize(original);
        var deserialized = serDes.deserialize(json, TestData.class);
        
        assertEquals(original, deserialized);
    }
    
    @Test
    void testNullHandling() {
        var serDes = new JacksonSerDes();
        
        assertNull(serDes.serialize(null));
        assertNull(serDes.deserialize(null, String.class));
    }
}
