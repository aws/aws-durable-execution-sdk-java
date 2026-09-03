// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.examples.general;

import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableHandler;
import software.amazon.lambda.durable.events.LambdaEventSerDes;

/**
 * Example demonstrating Lambda runtime serialization for an SQS event.
 *
 * <p>The extra SerDes module preserves Lambda event property mappings such as {@code eventSourceARN} when converting
 * the durable execution input into {@link SQSEvent}.
 */
public class LambdaEventSerDesExample extends DurableHandler<SQSEvent, String> {

    @Override
    protected DurableConfig createConfiguration() {
        return DurableConfig.builder().withSerDes(new LambdaEventSerDes()).build();
    }

    @Override
    public String handleRequest(SQSEvent input, DurableContext context) {
        var message = input.getRecords().get(0);
        return context.step(
                "read-sqs-message",
                String.class,
                stepContext -> message.getMessageId() + "|" + message.getBody() + "|" + message.getEventSourceArn());
    }
}
