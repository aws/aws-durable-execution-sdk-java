// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.time.Duration;
import software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.client.LambdaDurableFunctionsClient;
import software.amazon.lambda.durable.serde.SerDes;

/**
 * Example demonstrating custom configuration with both custom HTTP client and custom SerDes. Shows how to configure a
 * custom Apache HTTP client for the Lambda client while maintaining automatic credentials detection and region
 * fallback, plus a custom SerDes with snake_case naming.
 *
 * <p>This example demonstrates:
 *
 * <ul>
 *   <li>Custom Apache HTTP client configuration for improved performance
 *   <li>Automatic region detection with fallback to us-east-1 for testing environments
 *   <li>Environment variable credentials provider
 *   <li>Custom SerDes with snake_case property naming
 * </ul>
 */
public class CustomConfigExample extends DurableHandler<String, String> {

    @Override
    protected DurableConfig createConfiguration() {
        // Configure custom Apache HTTP client for better performance
        var httpClient = ApacheHttpClient.builder()
                .maxConnections(50)
                .connectionTimeout(Duration.ofSeconds(30))
                .socketTimeout(Duration.ofSeconds(60))
                .build();

        // Get region with fallback to us-east-1 if AWS_REGION not set
        // This prevents initialization failures in testing environments
        var region = System.getenv(SdkSystemSetting.AWS_REGION.environmentVariable());
        if (region == null || region.isEmpty()) {
            region = "us-east-1";
        }

        // Create Lambda client with custom HTTP client
        // Uses automatic credentials detection and region fallback
        var lambdaClient = LambdaClient.builder()
                .httpClient(httpClient)
                .credentialsProvider(EnvironmentVariableCredentialsProvider.create())
                .region(Region.of(region))
                .build();

        // Wrap the Lambda client with LambdaDurableFunctionsClient
        var durableClient = new LambdaDurableFunctionsClient(lambdaClient);

        // Create custom SerDes with snake_case naming
        var customSerDes = new SnakeCaseSerDes();

        return DurableConfig.builder()
                .withDurableExecutionClient(durableClient)
                .withSerDes(customSerDes)
                .build();
    }

    @Override
    public String handleRequest(String input, DurableContext context) {
        // Step 1: Create a custom object with camelCase fields to demonstrate snake_case serialization
        var customObject = context.step(
                "create-custom-object",
                CustomData.class,
                () -> new CustomData("user123", "John Doe", 25, "john.doe@example.com"));

        return "Created custom object: " + customObject.userId + ", " + customObject.fullName + ", "
                + customObject.userAge + ", " + customObject.emailAddress;
    }

    /**
     * Custom data class with camelCase field names to demonstrate snake_case serialization. The SerDes will convert
     * these field names to snake_case in the JSON output.
     */
    public static class CustomData {
        public String userId;
        public String fullName;
        public int userAge;
        public String emailAddress;

        public CustomData() {}

        public CustomData(String userId, String fullName, int userAge, String emailAddress) {
            this.userId = userId;
            this.fullName = fullName;
            this.userAge = userAge;
            this.emailAddress = emailAddress;
        }
    }

    /**
     * Custom SerDes implementation using snake_case property naming. Demonstrates how to provide custom serialization
     * behavior.
     */
    private static class SnakeCaseSerDes implements SerDes {
        private final ObjectMapper objectMapper;

        public SnakeCaseSerDes() {
            this.objectMapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        }

        @Override
        public String serialize(Object obj) {
            try {
                return objectMapper.writeValueAsString(obj);
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Serialization failed", e);
            }
        }

        @Override
        public <T> T deserialize(String json, TypeToken<T> typeToken) {
            try {
                return objectMapper.readValue(json, objectMapper.constructType(typeToken.getType()));
            } catch (IOException e) {
                throw new RuntimeException("Deserialization failed", e);
            }
        }
    }
}
