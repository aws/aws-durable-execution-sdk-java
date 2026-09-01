// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesRunner;

class TestOperationTest {
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST"
            + "/durable-execution/execution-id/invocation-id";

    @Test
    void deserializesStepResultWithDurableContext() {
        var observedContext = new AtomicReference<SerDesContext>();
        var serDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return value.toString();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                observedContext.set(SerDesContext.getCurrentContext());
                return (T) data;
            }
        };
        var operation = Operation.builder()
                .id("step-id")
                .name("step")
                .type(OperationType.STEP)
                .subType("Step")
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(
                        StepDetails.builder().attempt(2).result("step-result").build())
                .build();
        var testOperation = new TestOperation(operation, List.of(), serDes, new SerDesRunner(null), EXECUTION_ARN);

        assertEquals("step-result", testOperation.getStepResult(String.class));
        assertEquals(EXECUTION_ARN, observedContext.get().durableExecutionArn());
        assertEquals("step-id/result", observedContext.get().entityId());
    }
}
