// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import java.time.Duration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.lambda.durable.serde.JacksonSerDes;

public class CloudDurableTestRunner<I, O> {
    private final String functionArn;
    private final Class<I> inputType;
    private final Class<O> outputType;
    private final LambdaClient lambdaClient;
    private final Duration pollInterval;
    private final Duration timeout;
    private final InvocationType invocationType;
    // Store last execution result for operation inspection
    private TestResult<O> lastResult;

    private CloudDurableTestRunner(
            String functionArn,
            Class<I> inputType,
            Class<O> outputType,
            LambdaClient lambdaClient,
            Duration pollInterval,
            Duration timeout,
            InvocationType invocationType) {
        this.functionArn = functionArn;
        this.inputType = inputType;
        this.outputType = outputType;
        this.lambdaClient = lambdaClient;
        this.pollInterval = pollInterval;
        this.timeout = timeout;
        this.invocationType = invocationType;
    }

    public static <I, O> CloudDurableTestRunner<I, O> create(
            String functionArn, Class<I> inputType, Class<O> outputType) {
        return new CloudDurableTestRunner<>(
                functionArn,
                inputType,
                outputType,
                LambdaClient.builder()
                        .credentialsProvider(
                                DefaultCredentialsProvider.builder().build())
                        .build(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(300),
                InvocationType.REQUEST_RESPONSE);
    }

    public static <I, O> CloudDurableTestRunner<I, O> create(
            String functionArn, Class<I> inputType, Class<O> outputType, LambdaClient lambdaClient) {
        return new CloudDurableTestRunner<>(
                functionArn,
                inputType,
                outputType,
                lambdaClient,
                Duration.ofSeconds(2),
                Duration.ofSeconds(300),
                InvocationType.REQUEST_RESPONSE);
    }

    public CloudDurableTestRunner<I, O> withPollInterval(Duration interval) {
        return new CloudDurableTestRunner<>(
                functionArn, inputType, outputType, lambdaClient, interval, timeout, invocationType);
    }

    public CloudDurableTestRunner<I, O> withTimeout(Duration timeout) {
        return new CloudDurableTestRunner<>(
                functionArn, inputType, outputType, lambdaClient, pollInterval, timeout, invocationType);
    }

    public CloudDurableTestRunner<I, O> withInvocationType(InvocationType type) {
        return new CloudDurableTestRunner<>(
                functionArn, inputType, outputType, lambdaClient, pollInterval, timeout, type);
    }

    public TestResult<O> run(I input) {
        try {
            // Serialize input
            var serDes = new JacksonSerDes();
            var inputJson = serDes.serialize(input);

            // Invoke function
            var invokeRequest = InvokeRequest.builder()
                    .functionName(functionArn)
                    .invocationType(invocationType)
                    .payload(SdkBytes.fromUtf8String(inputJson))
                    .build();

            var response = lambdaClient.invoke(invokeRequest);

            // Extract execution ARN from response headers
            var executionArn = response.durableExecutionArn();
            if (executionArn == null) {
                throw new RuntimeException("No durable execution ARN returned - function may not be durable");
            }

            // Poll history until completion
            var poller = new HistoryPoller(lambdaClient);
            var events = poller.pollUntilComplete(executionArn, pollInterval, timeout);

            // Process events into TestResult
            var processor = new HistoryEventProcessor();
            var result = processor.processEvents(events, outputType);
            this.lastResult = result;
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Function invocation failed", e);
        }
    }

    /**
     * Start an asynchronous execution and return a handle for incremental polling. Use this for callback-based tests
     * where you need to interact with the execution while it's running.
     *
     * @param input the input to the function
     * @return execution handle for polling and inspection
     */
    public AsyncExecution<O> startAsync(I input) {
        try {
            // Serialize input
            var serDes = new JacksonSerDes();
            var inputJson = serDes.serialize(input);

            // Invoke function with EVENT type (async)
            var invokeRequest = InvokeRequest.builder()
                    .functionName(functionArn)
                    .invocationType(InvocationType.EVENT)
                    .payload(SdkBytes.fromUtf8String(inputJson))
                    .build();

            var response = lambdaClient.invoke(invokeRequest);

            // Extract execution ARN from response
            var executionArn = response.durableExecutionArn();
            if (executionArn == null) {
                throw new RuntimeException("No durable execution ARN returned - function may not be durable");
            }

            // Give the execution a moment to initialize before returning
            // This prevents immediate polling from failing with "execution does not exist"
            Thread.sleep(100);

            return new AsyncExecution<>(executionArn, lambdaClient, outputType, pollInterval, timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while starting async execution", e);
        } catch (Exception e) {
            throw new RuntimeException("Function invocation failed", e);
        }
    }

    public TestOperation getOperation(String name) {
        if (lastResult == null) {
            throw new IllegalStateException("No execution has been run yet");
        }
        return lastResult.getOperation(name);
    }
}
