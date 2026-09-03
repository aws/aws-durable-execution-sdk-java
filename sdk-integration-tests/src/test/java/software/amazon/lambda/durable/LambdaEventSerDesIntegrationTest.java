// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.lambda.durable.model.ExecutionStatus.SUCCEEDED;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.events.LambdaEventSerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class LambdaEventSerDesIntegrationTest {

    @Test
    void parsesSqsEventInputDuringDurableExecutionAndReplay() {
        var message = new SQSEvent.SQSMessage();
        message.setMessageId("message-1");
        message.setBody("hello from sqs");
        message.setEventSourceArn("arn:aws:sqs:us-east-1:123456789012:orders");

        var event = new SQSEvent();
        event.setRecords(List.of(message));

        var config = DurableConfig.builder().withSerDes(new LambdaEventSerDes()).build();
        var handlerRuns = new AtomicInteger();
        var stepRuns = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                SQSEvent.class,
                (input, context) -> {
                    handlerRuns.incrementAndGet();
                    var body = context.step("read-message", String.class, stepContext -> {
                        stepRuns.incrementAndGet();
                        return input.getRecords().get(0).getBody();
                    });
                    context.wait("resume", Duration.ofSeconds(1));
                    return body;
                },
                config);

        var result = runner.runUntilComplete(event);

        assertEquals(SUCCEEDED, result.getStatus());
        assertEquals("hello from sqs", result.getResult(String.class));
        assertTrue(handlerRuns.get() >= 2);
        assertEquals(1, stepRuns.get());
    }
}
