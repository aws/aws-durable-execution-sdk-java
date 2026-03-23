// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import static org.junit.jupiter.api.Assertions.*;
import static software.amazon.lambda.durable.TypeToken.get;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.CloudDurableTestRunner;

@EnabledIf("isEnabled")
class CloudBasedIntegrationTest {
    private static final int PERFORMANCE_TEST_REPEAT = 3;

    private static String account;
    private static String region;
    private static String functionNameSuffix;
    private static LambdaClient lambdaClient;

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
            try (var sts = StsClient.create()) {
                if (account == null) account = sts.getCallerIdentity().account();
                if (region == null)
                    region = sts.serviceClientConfiguration().region().id();
            }
        }

        lambdaClient = LambdaClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .region(Region.of(region))
                .build();

        System.out.println("☁️ Running cloud integration tests against account " + account + " in " + region);
    }

    private static String arn(String functionName) {
        return "arn:aws:lambda:" + region + ":" + account + ":function:" + functionName + functionNameSuffix
                + ":$LATEST";
    }

    @Test
    void testSimpleStepExample() {
        var runner = CloudDurableTestRunner.create(
                arn("simple-step-example"), new TypeToken<Map<String, String>>() {}, get(String.class), lambdaClient);
        var result = runner.run(Map.of("message", "test"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertNotNull(result.getResult(String.class));

        var createGreetingOp = runner.getOperation("create-greeting");
        assertNotNull(createGreetingOp);
        assertEquals("create-greeting", createGreetingOp.getName());
    }

    @Test
    void testNoopExampleWithLargeInput() {
        var runner = CloudDurableTestRunner.create(
                arn("noop-example"), new TypeToken<Map<String, String>>() {}, get(String.class), lambdaClient);
        // 6MB large input
        var largeInput = "A".repeat(1024 * 1024 * 6 - 12);
        var result = runner.run(Map.of("name", largeInput));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("HELLO, " + largeInput + "!", result.getResult(String.class));
    }

    @Test
    void testSimpleInvokeExample() {
        var runner = CloudDurableTestRunner.create(
                arn("simple-invoke-example"), new TypeToken<Map<String, String>>() {}, get(String.class), lambdaClient);
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
        var runner = CloudDurableTestRunner.create(arn("retry-example"), String.class, String.class, lambdaClient);
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
        var runner = CloudDurableTestRunner.create(
                arn("retry-in-process-example"), String.class, String.class, lambdaClient);
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
        var runner =
                CloudDurableTestRunner.create(arn("wait-example"), GreetingRequest.class, String.class, lambdaClient);
        var result = runner.run(new GreetingRequest("TestUser"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("Started processing for TestUser"));
        assertFalse(finalResult.contains("continued after 10s"));
        assertTrue(finalResult.contains("waited 5 seconds"));
        assertTrue(finalResult.contains("completed after 5s more"));

        assertNotNull(runner.getOperation("start-processing"));
        assertNotNull(runner.getOperation("continue-processing"));
        assertNotNull(runner.getOperation("complete-processing"));
    }

    @Test
    void testWaitAtLeastExample() {
        var runner = CloudDurableTestRunner.create(
                arn("wait-at-least-example"), GreetingRequest.class, String.class, lambdaClient);
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
                arn("wait-at-least-in-process-example"), GreetingRequest.class, String.class, lambdaClient);
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
                arn("generic-types-example"),
                GenericTypesExample.Input.class,
                GenericTypesExample.Output.class,
                lambdaClient);
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
    void testGenericInputOutputExample() {
        final TypeToken<Map<String, Map<String, List<String>>>> resultType = new TypeToken<>() {};
        final TypeToken<Map<String, String>> inputType = new TypeToken<>() {};

        var runner =
                CloudDurableTestRunner.create(arn("generic-input-output-example"), inputType, resultType, lambdaClient);
        var result = runner.run(new HashMap<>(Map.of("userId", "user123")));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var output = result.getResult(resultType);
        assertNotNull(output);

        // Verify categories nested map
        var categories = output.get("categories");
        assertNotNull(categories);
        assertEquals(3, categories.size());
        assertEquals(2, categories.get("electronics").size());
        assertTrue(categories.get("electronics").contains("laptop"));
        assertTrue(categories.get("electronics").contains("phone"));

        // Verify operations were executed
        assertNotNull(runner.getOperation("fetch-categories"));
    }

    @Test
    void testCustomConfigExample() {
        var runner =
                CloudDurableTestRunner.create(arn("custom-config-example"), String.class, String.class, lambdaClient);
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
        var runner =
                CloudDurableTestRunner.create(arn("error-handling-example"), String.class, String.class, lambdaClient);
        var result = runner.run("test-input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.startsWith("Completed: "));
        assertTrue(finalResult.contains("fallback-result"));
        assertTrue(finalResult.contains("payment-"));
    }

    @Test
    void testCallbackExample() {
        // happy case covering both createCallback (approval) and waitForCallback (preapproval-callback)
        var runner = CloudDurableTestRunner.create(
                arn("callback-example"), ApprovalRequest.class, String.class, lambdaClient);

        // Start async execution
        var execution = runner.startAsync(new ApprovalRequest("Purchase order", 5000.0));

        // Complete the preapproval callback
        execution.pollUntil(exec -> exec.hasCallback("preapproval-callback"));
        var preapprovalCallbackId = execution.getCallbackId("preapproval-callback");
        lambdaClient.sendDurableExecutionCallbackSuccess(
                req -> req.callbackId(preapprovalCallbackId).result(SdkBytes.fromUtf8String("\"preapproved\"")));

        // Wait for callback to appear
        execution.pollUntil(exec -> exec.hasCallback("approval"));

        // Get callback ID
        var callbackId = execution.getCallbackId("approval");
        assertNotNull(callbackId);

        // Complete the callback using AWS SDK
        lambdaClient.sendDurableExecutionCallbackSuccess(
                req -> req.callbackId(callbackId).result(SdkBytes.fromUtf8String("\"approved\"")));

        // Wait for execution to complete
        var result = execution.pollUntilComplete();
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("preapproved"));
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
        var runner = CloudDurableTestRunner.create(
                arn("callback-example"), ApprovalRequest.class, String.class, lambdaClient);

        // Start async execution
        var execution = runner.startAsync(new ApprovalRequest("Purchase order", 5000.0));

        execution.pollUntil(exec -> exec.hasCallback("preapproval-callback"));
        var preapprovalCallbackId = execution.getCallbackId("preapproval-callback");
        lambdaClient.sendDurableExecutionCallbackSuccess(
                req -> req.callbackId(preapprovalCallbackId).result(SdkBytes.fromUtf8String("\"preapproved\"")));

        // Wait for callback to appear
        execution.pollUntil(exec -> exec.hasCallback("approval"));

        // Get callback ID
        var callbackId = execution.getCallbackId("approval");
        assertNotNull(callbackId);

        // Fail the callback using AWS SDK
        lambdaClient.sendDurableExecutionCallbackFailure(req -> req.callbackId(callbackId)
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
        var runner = CloudDurableTestRunner.create(
                arn("callback-example"), ApprovalRequest.class, String.class, lambdaClient);

        // Start async execution with 10 second timeout
        var execution = runner.startAsync(new ApprovalRequest("Purchase order", 5000.0, 10));

        execution.pollUntil(exec -> exec.hasCallback("preapproval-callback"));
        var preapprovalCallbackId = execution.getCallbackId("preapproval-callback");
        lambdaClient.sendDurableExecutionCallbackSuccess(
                req -> req.callbackId(preapprovalCallbackId).result(SdkBytes.fromUtf8String("\"preapproved\"")));

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
    void testCallbackExampleWithWaitForCallbackFailure() {
        // fail the waitForCallback (preapproval-callback) callback
        var runner = CloudDurableTestRunner.create(
                arn("callback-example"), ApprovalRequest.class, String.class, lambdaClient);

        // Start async execution with 10 second timeout
        var execution = runner.startAsync(new ApprovalRequest("Purchase order", 5000.0, 10));

        execution.pollUntil(exec -> exec.hasCallback("preapproval-callback"));
        var preapprovalCallbackId = execution.getCallbackId("preapproval-callback");
        lambdaClient.sendDurableExecutionCallbackFailure(
                req -> req.callbackId(preapprovalCallbackId).error(err -> err.errorMessage("preapproval denied")));

        // Wait for callback to appear
        execution.pollUntil(exec -> exec.hasCallback("approval"));

        // Get callback ID but don't complete it - let it timeout
        var callbackId = execution.getCallbackId("approval");
        assertNotNull(callbackId);

        // Wait for execution to complete (should timeout after 10 seconds)
        var result = execution.pollUntilComplete();
        assertEquals(ExecutionStatus.FAILED, result.getStatus());

        // Verify the callback operation shows timeout status
        var approvalOp = execution.getOperation("preapproval-callback");
        assertNotNull(approvalOp);
        assertEquals(OperationStatus.FAILED, approvalOp.getStatus());
    }

    @Test
    void testChildContextExample() {
        var runner = CloudDurableTestRunner.create(
                arn("child-context-example"), GreetingRequest.class, String.class, lambdaClient);
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

    @ParameterizedTest
    @CsvSource({"100, 1000, 10", "500, 2000, 20", "1000, 3000, 50"})
    void testManyAsyncStepsExample(int steps, long maxExecutionTime, long maxReplayTime) {
        long minimalExecutionTimeMs = Long.MAX_VALUE;
        long minimalReplayTimeMs = Long.MAX_VALUE;
        for (var i = 0; i < PERFORMANCE_TEST_REPEAT; i++) {
            var runner = CloudDurableTestRunner.create(
                    arn("many-async-steps-example"),
                    ManyAsyncStepsExample.Input.class,
                    ManyAsyncStepsExample.Output.class,
                    lambdaClient);
            var result = runner.run(new ManyAsyncStepsExample.Input(2, steps));

            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

            var finalResult = result.getResult(ManyAsyncStepsExample.Output.class);
            System.out.printf("ManyAsyncStepsExample result (%d steps): %s\n", steps, finalResult);
            assertNotNull(finalResult);
            assertEquals((long) steps * (steps - 1), finalResult.result()); // Sum of 0..steps * 2

            // Verify some operations are tracked
            assertNotNull(runner.getOperation("compute-0"));
            assertNotNull(runner.getOperation("compute-" + (steps - 1)));

            if (finalResult.executionTimeMs() < minimalExecutionTimeMs) {
                minimalExecutionTimeMs = finalResult.executionTimeMs();
            }

            if (finalResult.replayTimeMs() < minimalReplayTimeMs) {
                minimalReplayTimeMs = finalResult.replayTimeMs();
            }
        }

        assertTrue(minimalReplayTimeMs < maxReplayTime);
        assertTrue(minimalExecutionTimeMs < maxExecutionTime);
    }

    @ParameterizedTest
    // OOM if it creates 1000 child contexts
    @CsvSource({"100, 1500, 10", "500, 3000, 20"})
    void testManyAsyncChildContextExample(int steps, long maxExecutionTime, long maxReplayTime) {
        long minimalExecutionTimeMs = Long.MAX_VALUE;
        long minimalReplayTimeMs = Long.MAX_VALUE;
        for (var i = 0; i < PERFORMANCE_TEST_REPEAT; i++) {
            var runner = CloudDurableTestRunner.create(
                    arn("many-async-child-context-example"),
                    ManyAsyncChildContextExample.Input.class,
                    ManyAsyncChildContextExample.Output.class,
                    lambdaClient);
            var result = runner.run(new ManyAsyncChildContextExample.Input(2, steps));

            assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

            var finalResult = result.getResult(ManyAsyncChildContextExample.Output.class);
            System.out.printf("ManyAsyncChildContextExample result (%d child contexts): %s\n", steps, finalResult);
            assertNotNull(finalResult);
            assertEquals((long) steps * (steps - 1), finalResult.result()); // Sum of 0..steps * 2

            // Verify some operations are tracked
            assertNotNull(runner.getOperation("compute-0"));
            assertNotNull(runner.getOperation("compute-" + (steps - 1)));
            assertNotNull(runner.getOperation("child-0"));
            assertNotNull(runner.getOperation("child-" + (steps - 1)));

            if (finalResult.executionTimeMs() < minimalExecutionTimeMs) {
                minimalExecutionTimeMs = finalResult.executionTimeMs();
            }

            if (finalResult.replayTimeMs() < minimalReplayTimeMs) {
                minimalReplayTimeMs = finalResult.replayTimeMs();
            }
        }

        assertTrue(minimalReplayTimeMs < maxReplayTime);
        assertTrue(minimalExecutionTimeMs < maxExecutionTime);
    }

    @Test
    void testSimpleMapExample() {
        var runner = CloudDurableTestRunner.create(
                arn("simple-map-example"), GreetingRequest.class, String.class, lambdaClient);
        var result = runner.run(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("Hello, Alice! | Hello, ALICE! | Hello, alice!", result.getResult(String.class));
    }

    @Test
    void testComplexMapExample() {
        var runner = CloudDurableTestRunner.create(arn("complex-map-example"), GreetingRequest.class, String.class);
        var result = runner.run(new GreetingRequest("Alice"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        var output = result.getResult(String.class);
        assertNotNull(output);

        // Part 1: Concurrent order processing with step + wait + step
        assertTrue(output.contains("done:validated:order-1:Alice"));
        assertTrue(output.contains("done:validated:order-2:Alice"));
        assertTrue(output.contains("done:validated:order-3:Alice"));

        // Part 2: Early termination — find 2 healthy servers then stop
        assertTrue(output.contains("healthy"));
        assertTrue(output.contains("reason=MIN_SUCCESSFUL_REACHED"));
    }
}
