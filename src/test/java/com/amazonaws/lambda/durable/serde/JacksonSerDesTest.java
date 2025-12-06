package com.amazonaws.lambda.durable.serde;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JacksonSerDesTest {
    
    @Test
    void testRoundTrip() {
        var serDes = new JacksonSerDes();
        
        // Test String
        var original = "Test Value";
        var json = serDes.serialize(original);
        var restored = serDes.deserialize(json, String.class);
        assertEquals(original, restored);
        
        // Test Integer
        var number = 42;
        var numberJson = serDes.serialize(number);
        var restoredNumber = serDes.deserialize(numberJson, Integer.class);
        assertEquals(number, restoredNumber);
    }
}
