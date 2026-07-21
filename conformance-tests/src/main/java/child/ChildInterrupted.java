// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package child;

import java.util.Map;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.config.StepConfig;

/** 3-12: Child context interrupted and re-executed */
public class ChildInterrupted extends DurableHandler<String, String> {

    private static final DynamoDbClient DDB_CLIENT = DynamoDbClient.create();
    private static final String TABLE_NAME =
            System.getenv("ATTEMPTS_TABLE_NAME") != null ? System.getenv("ATTEMPTS_TABLE_NAME") : "JavaAttempts";

    @Override
    public String handleRequest(String input, DurableContext context) {
        String executionId = context.getExecutionArn();

        return context.runInChildContext(
                "interrupted-child",
                String.class,
                child -> child.step(
                        "crashable-step",
                        String.class,
                        stepCtx -> {
                            UpdateItemResponse response = DDB_CLIENT.updateItem(UpdateItemRequest.builder()
                                    .tableName(TABLE_NAME)
                                    .key(Map.of(
                                            "executionId",
                                            AttributeValue.builder()
                                                    .s(executionId)
                                                    .build()))
                                    .updateExpression("SET attemptCount = if_not_exists(attemptCount, :zero) + :inc")
                                    .expressionAttributeValues(Map.of(
                                            ":zero",
                                                    AttributeValue.builder()
                                                            .n("0")
                                                            .build(),
                                            ":inc",
                                                    AttributeValue.builder()
                                                            .n("1")
                                                            .build()))
                                    .returnValues(ReturnValue.UPDATED_NEW)
                                    .build());

                            int attemptCount = Integer.parseInt(
                                    response.attributes().get("attemptCount").n());
                            if (attemptCount < 2) {
                                // Sleep to allow checkpoint to be sent before crash
                                try {
                                    Thread.sleep(1000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                // Simulate a crash/interrupt on first attempt
                                System.exit(1);
                            }
                            return input;
                        },
                        StepConfig.builder().build()));
    }
}
