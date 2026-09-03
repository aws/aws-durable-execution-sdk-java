// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class LambdaEventSerDesExampleTest {

    @Test
    void deserializesSqsEventWithLambdaRuntimeMappings() {
        var message = new SQSEvent.SQSMessage();
        message.setMessageId("message-1");
        message.setBody("hello from sqs");
        message.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:orders");

        var event = new SQSEvent();
        event.setRecords(List.of(message));

        var result = LocalDurableTestRunner.create(SQSEvent.class, new LambdaEventSerDesExample())
                .run(event);

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertTrue(result.getResult(String.class).contains("message-1|hello from sqs|"));
        assertTrue(result.getResult(String.class).contains("arn:aws:sqs:us-east-1:123456789012:orders"));
    }
}
