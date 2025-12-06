package com.amazonaws.lambda.durable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurableContextTest {
    
    @Test
    void testSimpleStep() {
        var ctx = new DurableContext();
        
        var result = ctx.step("test", String.class, () -> "Hello");
        
        assertEquals("Hello", result);
    }
    
    @Test
    void testStepWithComputation() {
        var ctx = new DurableContext();
        
        var result = ctx.step("add", Integer.class, () -> 5 + 3);
        
        assertEquals(8, result);
    }
    
    @Test
    void testCheckpointLog() {
        var ctx = new DurableContext();
        
        ctx.step("step1", String.class, () -> "A");
        ctx.step("step2", Integer.class, () -> 42);
        
        var log = ctx.getCheckpointLog();
        
        assertEquals(2, log.size());
        assertEquals("step1", log.get(0).getName());
        assertEquals("\"A\"", log.get(0).getResult());  // JSON string includes quotes
        assertEquals("step2", log.get(1).getName());
        assertEquals("42", log.get(1).getResult());
    }
    
    @Test
    void testReplay() {
        var ctx = new DurableContext();
        
        // First execution
        var result1 = ctx.step("test", String.class, () -> "first");
        assertEquals("first", result1);
        
        // Create new context with checkpoint log (simulates replay)
        var ctx2 = new DurableContext(ctx.getCheckpointLog());
        
        // Second execution - should return cached result, not "second"
        var result2 = ctx2.step("test", String.class, () -> "second");
        assertEquals("first", result2);  // Gets cached "first", not "second"
    }
    
    @Test
    void testComplexTypeReplay() {
        var ctx = new DurableContext();
        
        // Execute with Integer
        var result1 = ctx.step("compute", Integer.class, () -> 42);
        assertEquals(42, result1);
        
        // Create new context with checkpoint log
        var ctx2 = new DurableContext(ctx.getCheckpointLog());
        
        // Replay - should deserialize correctly as Integer, not String
        var result2 = ctx2.step("compute", Integer.class, () -> 999);
        assertEquals(42, result2);  // Gets cached 42, not 999
    }
}
