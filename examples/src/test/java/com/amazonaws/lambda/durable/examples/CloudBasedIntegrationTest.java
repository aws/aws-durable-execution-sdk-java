package com.amazonaws.lambda.durable.examples;

import com.amazonaws.lambda.durable.model.ExecutionStatus;
import com.amazonaws.lambda.durable.testing.CloudDurableTestRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIf("isEnabled")
public class CloudBasedIntegrationTest {

    private static String account;
    private static String region;

    static boolean isEnabled() {
        var enabled = "true".equals(System.getProperty("test.cloud.enabled"));
        if (!enabled) {
            System.out.println("⚠️  Cloud integration tests disabled. Enable with -Dtest.cloud.enabled=true");
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

        if (account == null || region == null) {
            var sts = StsClient.create();
            if (account == null) account = sts.getCallerIdentity().account();
            if (region == null) region = sts.serviceClientConfiguration().region().id();
        }

        System.out.println("☁️  Running cloud integration tests against account " + account + " in " + region);
    }

    private static String arn(String functionName) {
        return "arn:aws:lambda:" + region + ":" + account + ":function:" + functionName + ":$LATEST";
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
        var runner = CloudDurableTestRunner.create(arn("wait-at-least-in-process-example"), GreetingRequest.class, String.class);
        var result = runner.run(new GreetingRequest("TestUser"));

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());

        var finalResult = result.getResult(String.class);
        assertNotNull(finalResult);
        assertTrue(finalResult.contains("Processed: TestUser"));

        var asyncOp = runner.getOperation("async-operation");
        assertNotNull(asyncOp);
        assertTrue(asyncOp.getStepResult(String.class).contains("Processed: TestUser"));
    }
}
