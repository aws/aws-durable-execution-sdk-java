// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package com.amazonaws.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.CloudDurableTestRunner;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.sts.StsClient;

@EnabledIf("isEnabled")
class CloudBasedIntegrationTest {

    private static String account;
    private static String region;
    private static String functionNameSuffix;

    static boolean isEnabled() {
        var enabled = "true".equals(System.getProperty("test.cloud.enabled"));
        if (!enabled) {
            System.out.println("⚠️ Cloud integration tests disabled. Enable with -Dtest.cloud.enabled=true");
        }
        return enabled;
    }

    @BeforeAll
    static void setup() {
        try {
            DefaultCredentialsProvider.builder().build().resolveCredentials();
        } catch (Exception e) {
            throw new IllegalStateException("AWS credentials not available");
        }

        account = System.getProperty("test.aws.account");
        region = System.getProperty("test.aws.region");
        functionNameSuffix = System.getProperty("test.function.name.suffix", "");

        if (account == null || region == null) {
            var sts = StsClient.create();
            if (account == null) account = sts.getCallerIdentity().account();
            if (region == null)
                region = sts.serviceClientConfiguration().region().id();
        }

        System.out.println("☁️ Running cloud integration tests against account " + account + " in " + region);
    }

    private static String arn(String functionName) {
        return "arn:aws:lambda:" + region + ":" + account + ":function:" + functionName + functionNameSuffix
                + ":$LATEST";
    }

    @Test
    void testSimpleStepExample() {
        var runner = CloudDurableTestRunner.create(arn("simple-step-example"), Map.class, String.class);
        var result = runner.run(Map.of("message", "test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertNotNull(result.getResult(String.class));

        var createGreetingOp = runner.getOperation("create-greeting");
        assertNotNull(createGreetingOp);
        assertEquals("create-greeting", createGreetingOp.getName());
    }

    @Test
    void testSimpleInvokeExample() {
        var runner = CloudDurableTestRunner.create(arn("simple-invoke-example"), Map.class, String.class);
        var result = runner.run(Map.of("name", functionNameSuffix));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertNotNull(result.getResult(String.class));

        var createGreetingOp = runner.getOperation("call-greeting1");
        assertNotNull(createGreetingOp);
        assertEquals("call-greeting1", createGreetingOp.getName());

        var createGreetingOp2 = runner.getOperation("call-greeting2");
        assertNotNull(createGreetingOp);
        assertEquals("call-greeting2", createGreetingOp2.getName());
    }

    @Test
    void testRetryExample() {
        var runner = CloudDurableTestRunner.create(arn("retry-example"), String.class, String.class);
        var result = runner.run("{}");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("Retry example completed"));
        assertTrue(finalResult.contains("Flaky API succeeded"));

        var recordStartOp = runner.getOperation("record-start-time");
        assertNotNull(recordStartOp);

        var flakyApiOp = runner.getOperation("flaky-api-call");
        assertNotNull(flakyApiOp);
        assertTrue(flakyApiOp.getStepResult(String.class).contains("Flaky API succeeded"));
    }

    @Test
    void testRetryInProcessExample() {
        var runner = CloudDurableTestRunner.create(arn("retry-in-process-example"), String.class, String.class);
        var result = runner.run("{}");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("Retry in-process completed"));
        assertTrue(finalResult.contains("Long operation completed"));
        assertTrue(finalResult.contains("Async operation succeeded"));

        var asyncOp = runner.getOperation("flaky-async-operation");
        assertNotNull(asyncOp);
        assertTrue(asyncOp.getStepResult(String.class).contains("Async operation succeeded"));

        var longOp = runner.getOperation("long-running-operation");
        assertNotNull(longOp);
        assertEquals("Long operation completed", longOp.getStepResult(String.class));
    }

    @Test
    void testWaitExample() {
        var runner = CloudDurableTestRunner.create(arn("wait-example"), GreetingRequest.class, String.class);
        var result = runner.run(new GreetingRequest("TestUser"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("Started processing for TestUser"));
        assertTrue(finalResult.contains("continued after 10s"));
        assertTrue(finalResult.contains("completed after 5s more"));

        assertNotNull(runner.getOperation("start-processing"));
        assertNotNull(runner.getOperation("continue-processing"));
        assertNotNull(runner.getOperation("complete-processing"));
    }

    @Test
    void testWaitAtLeastExample() {
        var runner = CloudDurableTestRunner.create(arn("wait-at-least-example"), GreetingRequest.class, String.class);
        var result = runner.run(new GreetingRequest("TestUser"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("Processed: TestUser"));

        var asyncOp = runner.getOperation("async-operation");
        assertNotNull(asyncOp);
        assertTrue(asyncOp.getStepResult(String.class).contains("Processed: TestUser"));
    }

    @Test
    void testWaitAtLeastInProcessExample() {
        var runner = CloudDurableTestRunner.create(
                arn("wait-at-least-in-process-example"), GreetingRequest.class, String.class);
        var result = runner.run(new GreetingRequest("TestUser"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("Processed: TestUser"));

        var asyncOp = runner.getOperation("async-operation");
        assertNotNull(asyncOp);
        assertTrue(asyncOp.getStepResult(String.class).contains("Processed: TestUser"));
    }

    @Test
    void testGenericTypesExample() {
        var runner = CloudDurableTestRunner.create(
                arn("generic-types-example"), GenericTypesExample.Input.class, GenericTypesExample.Output.class);
        var result = runner.run(new GenericTypesExample.Input("user123"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        GenericTypesExample.Output output = result.getResult(GenericTypesExample.Output.class);
        assertNotNull(output);

        // Verify items list
        assertNotNull(output.items);
        assertEquals(4, output.items.size());
        assertTrue(output.items.contains("item1"));
        assertTrue(output.items.contains("item4"));

        // Verify counts map
        assertNotNull(output.counts);
        assertEquals(3, output.counts.size());
        assertEquals(2, output.counts.get("electronics"));
        assertEquals(1, output.counts.get("books"));
        assertEquals(1, output.counts.get("clothing"));

        // Verify categories nested map
        assertNotNull(output.categories);
        assertEquals(3, output.categories.size());
        assertEquals(2, output.categories.get("electronics").size());
        assertTrue(output.categories.get("electronics").contains("laptop"));
        assertTrue(output.categories.get("electronics").contains("phone"));

        // Verify operations were executed
        assertNotNull(runner.getOperation("fetch-items"));
        assertNotNull(runner.getOperation("count-by-category"));
        assertNotNull(runner.getOperation("fetch-categories"));
    }

    @Test
    void testCustomConfigExample() {
        var runner = CloudDurableTestRunner.create(arn("custom-config-example"), String.class, String.class);
        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("Created custom object"));
        assertTrue(finalResult.contains("user123"));
        assertTrue(finalResult.contains("John Doe"));
        assertTrue(finalResult.contains("25"));
        assertTrue(finalResult.contains("john.doe@example.com"));

        // Verify the step operation was executed
        var createObjectOp = runner.getOperation("create-custom-object");
        assertNotNull(createObjectOp);
        assertEquals("create-custom-object", createObjectOp.getName());

        // The step result should contain the serialized JSON with snake_case
        var stepResult = createObjectOp.getStepDetails().result();
        assertNotNull(stepResult);
        assertTrue(stepResult.contains("user_id"));
        assertTrue(stepResult.contains("full_name"));
        assertTrue(stepResult.contains("user_age"));
        assertTrue(stepResult.contains("email_address"));
    }

    @Test
    void testErrorHandlingExample() {
        var runner = CloudDurableTestRunner.create(arn("error-handling-example"), String.class, String.class);
        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.startsWith("Completed: "));
        assertTrue(finalResult.contains("fallback-result"));
        assertTrue(finalResult.contains("payment-"));
    }

    @Test
    void testCallbackExample() throws Exception {
        var runner = CloudDurableTestRunner.create(arn("callback-example"), ApprovalRequest.class, String.class);

        // Start async execution
        var execution = runner.startAsync(new ApprovalRequest("Purchase order", 5000.0));

        // Wait for callback to appear
        execution.pollUntil(exec -> exec.hasCallback("approval"));

        // Get callback ID
        var callbackId = execution.getCallbackId("approval");
        assertNotNull(callbackId);

        // Complete the callback using AWS SDK
        var lambda = LambdaClient.create();
        lambda.sendDurableExecutionCallbackSuccess(
                req -> req.callbackId(callbackId).result(SdkBytes.fromUtf8String("\"approved\"")));

        // Wait for execution to complete
        var result = execution.pollUntilComplete();
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("Approval request for: Purchase order"));
        assertTrue(finalResult.contains("5000"));
        assertTrue(finalResult.contains("approved"));

        // Verify all operations completed
        assertNotNull(execution.getOperation("prepare"));
        assertNotNull(execution.getOperation("log-callback-command"));
        assertNotNull(execution.getOperation("process-approval"));
    }

    @Test
    void testCallbackExampleWithFailure() {
        var runner = CloudDurableTestRunner.create(arn("callback-example"), ApprovalRequest.class, String.class);

        // Start async execution
        var execution = runner.startAsync(new ApprovalRequest("Purchase order", 5000.0));

        // Wait for callback to appear
        execution.pollUntil(exec -> exec.hasCallback("approval"));

        // Get callback ID
        var callbackId = execution.getCallbackId("approval");
        assertNotNull(callbackId);

        // Fail the callback using AWS SDK
        var lambda = LambdaClient.create();
        lambda.sendDurableExecutionCallbackFailure(req -> req.callbackId(callbackId)
                .error(err -> err.errorType("ApprovalRejected").errorMessage("Approval rejected by manager")));

        // Wait for execution to complete
        var result = execution.pollUntilComplete();
        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // Verify the callback operation shows failure
        var approvalOp = execution.getOperation("approval");
        assertNotNull(approvalOp);
        var callbackDetails = approvalOp.getCallbackDetails();
        assertNotNull(callbackDetails);
        assertNotNull(callbackDetails.error());
        // Error message is redacted in the response, just verify error exists
        assertTrue(callbackDetails.error().toString().contains("ErrorObject"));
    }

    @Test
    void testCallbackExampleWithTimeout() {
        var runner = CloudDurableTestRunner.create(arn("callback-example"), ApprovalRequest.class, String.class);

        // Start async execution with 10 second timeout
        var execution = runner.startAsync(new ApprovalRequest("Purchase order", 5000.0, 10));

        // Wait for callback to appear
        execution.pollUntil(exec -> exec.hasCallback("approval"));

        // Get callback ID but don't complete it - let it timeout
        var callbackId = execution.getCallbackId("approval");
        assertNotNull(callbackId);

        // Wait for execution to complete (should timeout after 10 seconds)
        var result = execution.pollUntilComplete();
        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // Verify the callback operation shows timeout status
        var approvalOp = execution.getOperation("approval");
        assertNotNull(approvalOp);
        assertEquals(OperationStatus.TIMED_OUT, approvalOp.getStatus());
    }

    @Test
    void testChildContextExample() {
        var runner = CloudDurableTestRunner.create(arn("child-context-example"), GreetingRequest.class, String.class);
        var result = runner.run(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(
                "Order for Alice [validated] | Stock available for Alice [confirmed] | Base rate for Alice + regional adjustment [shipping ready]",
                result.getResult(String.class));

        // Verify child context operations were tracked
        assertNotNull(runner.getOperation("order-validation"));
        assertNotNull(runner.getOperation("inventory-check"));
        assertNotNull(runner.getOperation("shipping-estimate"));
    }

    @Test
    void testManyAsyncStepsExample() {
        var runner = CloudDurableTestRunner.create(
                arn("many-async-steps-example"), ManyAsyncStepsExample.Input.class, String.class);
        var result = runner.run(new ManyAsyncStepsExample.Input(2));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("500 async steps"));
        assertTrue(finalResult.contains("249500")); // Sum of 0..499 * 2

        // Verify some operations are tracked
        assertNotNull(runner.getOperation("compute-0"));
        assertNotNull(runner.getOperation("compute-499"));
    }
}
