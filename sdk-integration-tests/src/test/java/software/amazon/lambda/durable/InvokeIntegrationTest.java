// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/** Some example test to test end to end behavior * */
class InvokeIntegrationTest {

    static class TestInput {
        public String value;

        public TestInput() {}

        public TestInput(String value) {
            this.value = value;
        }
    }

    static class TestOutput {
        public String result;

        public TestOutput() {}

        public TestOutput(String result) {
            this.result = result;
        }
    }

    @Test
    void testInvokeWithSuccessResult() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var result = context.invoke("invoke", "chained-function", "{}", String.class);
            assertEquals("test output", result);
            return new TestOutput(result);
        });

        var output1 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());
        assertEquals(0, output1.getSucceededOperations().size());

        runner.completeChainedInvoke("invoke", "\"test output\"");
        var output2 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.SUCCEEDED, output2.getStatus());
        assertEquals("test output", output2.getResult(TestOutput.class).result);
        assertEquals(1, output2.getSucceededOperations().size());
    }

    @Test
    void testMultipleInvokeAsyncWithSuccessResult() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            var future1 = context.invokeAsync("invoke1", "chained-function-1", "{}", String.class);
            var future2 = context.invokeAsync("invoke2", "chained-function-2", "{}", String.class);
            var result1 = future1.get();
            assertEquals("test output 1", result1);
            var result2 = future2.get();
            assertEquals("test output 2", result2);
            return new TestOutput(future2.get());
        });

        var output1 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());
        assertEquals(0, output1.getSucceededOperations().size());

        runner.completeChainedInvoke("invoke1", "\"test output 1\"");
        var output2 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.PENDING, output2.getStatus());
        assertEquals(1, output2.getSucceededOperations().size());

        runner.completeChainedInvoke("invoke2", "\"test output 2\"");
        var output3 = runner.run(new TestInput("test"));

        assertEquals("test output 2", output3.getResult(TestOutput.class).result);
        assertEquals(2, output3.getSucceededOperations().size());
    }

    @Test
    void testInvokeWithFailedResults() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            try {
                var result = context.invoke("invoke", "chained-function", "{}", String.class);
                return new TestOutput(result);
            } catch (InvokeFailedException ex) {
                assertEquals("error output", ex.getMessage());
                assertEquals("error data", ex.getErrorObject().errorData());
                assertEquals("error type", ex.getErrorObject().errorType());
                throw ex;
            }
        });

        var output1 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());
        assertEquals(0, output1.getSucceededOperations().size());

        runner.failChainedInvoke(
                "invoke",
                ErrorObject.builder()
                        .errorMessage("error output")
                        .errorType("error type")
                        .errorData("error data")
                        .build());
        var output2 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.FAILED, output2.getStatus());
        ErrorObject error = output2.getError().orElseThrow();
        assertEquals("error type", error.errorType());
        assertEquals("error output", error.errorMessage());
    }

    @Test
    void testInvokeWithStoppedResults() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            try {
                var result = context.invoke("invoke", "chained-function", "{}", String.class);
                return new TestOutput(result);
            } catch (InvokeFailedException ex) {
                assertEquals("error output", ex.getMessage());
                assertEquals("error data", ex.getErrorObject().errorData());
                assertEquals("error type", ex.getErrorObject().errorType());
                throw ex;
            }
        });

        var output1 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());
        assertEquals(0, output1.getSucceededOperations().size());

        runner.stopChainedInvoke(
                "invoke",
                ErrorObject.builder()
                        .errorMessage("error output")
                        .errorType("error type")
                        .errorData("error data")
                        .build());
        var output2 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.FAILED, output2.getStatus());
        ErrorObject error = output2.getError().orElseThrow();
        assertEquals("error type", error.errorType());
        assertEquals("error output", error.errorMessage());
    }

    @Test
    void testInvokeWithTimeoutResults() {
        var runner = LocalDurableTestRunner.create(TestInput.class, (input, context) -> {
            try {
                var result = context.invoke("invoke", "chained-function", "{}", String.class);
                return new TestOutput(result);
            } catch (InvokeFailedException ex) {
                assertNull(ex.getMessage());
                assertNull(ex.getErrorObject().errorData());
                assertNull(ex.getErrorObject().errorType());
                throw ex;
            }
        });

        var output1 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.PENDING, output1.getStatus());
        assertEquals(0, output1.getSucceededOperations().size());

        runner.timeoutChainedInvoke("invoke");
        var output2 = runner.run(new TestInput("test"));

        assertEquals(ExecutionStatus.FAILED, output2.getStatus());
        assertFalse(output2.getError().isPresent());
    }
}
