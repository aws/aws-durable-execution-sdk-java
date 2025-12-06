package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocalDurableTestRunnerTest {
    
    @Test
    void testSimpleExecution() {
        var runner = new LocalDurableTestRunner<String, String>(
            String.class,
            (input, ctx) -> {
                var result = ctx.step("process", String.class, () -> "Hello, " + input);
                return result;
            }
        );
        
        var testResult = runner.run("World");
        
        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals("Hello, World", testResult.getResult(String.class));
    }
    
    @Test
    void testMultipleSteps() {
        var runner = new LocalDurableTestRunner<Integer, Integer>(
            Integer.class,
            (input, ctx) -> {
                var step1 = ctx.step("add", Integer.class, () -> input + 10);
                var step2 = ctx.step("multiply", Integer.class, () -> step1 * 2);
                var step3 = ctx.step("subtract", Integer.class, () -> step2 - 5);
                return step3;
            }
        );
        
        var testResult = runner.run(5);
        
        assertEquals(ExecutionStatus.SUCCEEDED, testResult.getStatus());
        assertEquals(25, testResult.getResult(Integer.class));  // (5 + 10) * 2 - 5 = 25
    }
}
