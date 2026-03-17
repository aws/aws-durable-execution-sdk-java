// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.ParameterizedType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.serde.DurableInputOutputSerDes;

/**
 * Abstract base class for Lambda handlers that use durable execution.
 *
 * <p>Extend this class and implement {@link #handleRequest(Object, DurableContext)} to build resilient, multi-step
 * workflows. The handler automatically manages checkpoint-and-replay, input deserialization, and communication with the
 * Lambda Durable Functions backend.
 *
 * @param <I> the input type
 * @param <O> the output type
 */
public abstract class DurableHandler<I, O> implements RequestStreamHandler {

    private final TypeToken<I> inputType;
    private final DurableConfig config;
    private final DurableInputOutputSerDes serDes = new DurableInputOutputSerDes(); // Internal ObjectMapper
    private static final Logger logger = LoggerFactory.getLogger(DurableHandler.class);

    protected DurableHandler() {
        // Extract input type from generic superclass
        var superClass = getClass().getGenericSuperclass();
        if (superClass instanceof ParameterizedType paramType) {
            this.inputType = TypeToken.get(paramType.getActualTypeArguments()[0]);
        } else {
            throw new IllegalArgumentException("Cannot determine input type parameter");
        }
        this.config = createConfiguration();
    }

    /**
     * Gets the configuration used by this handler. This allows test frameworks and other tools to access the handler's
     * configuration for testing purposes.
     *
     * <p>DurableConfig is immutable.
     *
     * @return The DurableConfig instance used by this handler
     */
    public DurableConfig getConfiguration() {
        return config;
    }

    /**
     * Template method for creating configuration. Override this method to provide custom DurableExecutionClient,
     * SerDes, or other configuration.
     *
     * <p>The {@link software.amazon.lambda.durable.client.LambdaDurableFunctionsClient} is a wrapper that customers
     * should use to inject their own configured {@link software.amazon.awssdk.services.lambda.LambdaClient}. This
     * allows full control over AWS SDK configuration including credentials, region, HTTP client, and retry policies.
     *
     * <p>Basic example with custom region and credentials:
     *
     * <pre>{@code
     * @Override
     * protected DurableConfig createConfiguration() {
     *     // Create custom Lambda client with specific configuration
     *     var lambdaClient = LambdaClient.builder()
     *         .region(Region.US_WEST_2)
     *         .credentialsProvider(ProfileCredentialsProvider.create("my-profile"))
     *         .build();
     *
     *     // Wrap the Lambda client with LambdaDurableFunctionsClient
     *     var durableClient = new LambdaDurableFunctionsClient(lambdaClient);
     *
     *     return DurableConfig.builder()
     *         .withDurableExecutionClient(durableClient)
     *         .build();
     * }
     * }</pre>
     *
     * <p>Advanced example with AWS CRT HTTP Client for high-performance scenarios:
     *
     * <pre>{@code
     * @Override
     * protected DurableConfig createConfiguration() {
     *     // Configure AWS CRT HTTP Client for optimal performance
     *     var crtHttpClient = AwsCrtAsyncHttpClient.builder()
     *         .maxConcurrency(50)
     *         .connectionTimeout(Duration.ofSeconds(30))
     *         .connectionMaxIdleTime(Duration.ofSeconds(60))
     *         .build();
     *
     *     // Create Lambda client with CRT HTTP client
     *     var lambdaClient = LambdaClient.builder()
     *         .region(Region.US_EAST_1)
     *         .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
     *         .httpClient(crtHttpClient)
     *         .overrideConfiguration(ClientOverrideConfiguration.builder()
     *             .retryPolicy(RetryPolicy.builder()
     *                 .numRetries(5)
     *                 .build())
     *             .build())
     *         .build();
     *
     *     // Wrap with LambdaDurableFunctionsClient
     *     var durableClient = new LambdaDurableFunctionsClient(lambdaClient);
     *
     *     return DurableConfig.builder()
     *         .withDurableExecutionClient(durableClient)
     *         .withSerDes(customSerDes)  // Optional: custom SerDes for user data
     *         .withExecutorService(customExecutor)  // Optional: custom thread pool
     *         .build();
     * }
     * }</pre>
     *
     * @return DurableConfig with desired configuration
     */
    protected DurableConfig createConfiguration() {
        return DurableConfig.defaultConfig();
    }

    /**
     * Reads the request, executes the durable function handler and writes the response
     *
     * @param inputStream the input stream
     * @param outputStream the output stream
     * @param context the Lambda context
     * @throws IOException thrown when serialize/deserialize fails
     */
    @Override
    public final void handleRequest(InputStream inputStream, OutputStream outputStream, Context context)
            throws IOException {
        var inputString = new String(inputStream.readAllBytes());
        logger.debug("Raw input from durable handler: {}", inputString);
        var input = serDes.deserialize(inputString, TypeToken.get(DurableExecutionInput.class));
        var output = DurableExecutor.execute(input, context, inputType, this::handleRequest, config);
        outputStream.write(serDes.serialize(output).getBytes());
    }

    /**
     * Handle the durable execution.
     *
     * @param input User input
     * @param context Durable context for operations
     * @return Result
     */
    public abstract O handleRequest(I input, DurableContext context);
}
