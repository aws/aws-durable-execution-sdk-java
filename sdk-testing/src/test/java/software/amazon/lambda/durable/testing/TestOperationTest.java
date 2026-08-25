// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesRunner;
import software.amazon.lambda.durable.serde.SerDesStage;

class TestOperationTest {
    private static final String EXECUTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test:$LATEST"
            + "/durable-execution/execution-id/invocation-id";

    @Test
    void failedWaitForConditionReadsStateFromPreviousAttempt() {
        var observedContext = new AtomicReference<SerDesContext>();
        var valueCodec = new SerDes() {
            @Override
            public String serialize(Object value) {
                return value.toString();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return (T) data;
            }
        };
        var serDes = valueCodec.then(new SerDesStage() {
            @Override
            public String serialize(String value, SerDesContext context) {
                return value;
            }

            @Override
            public String deserialize(String data, SerDesContext context) {
                observedContext.set(context);
                return data;
            }
        });
        var operation = Operation.builder()
                .id("wait-id")
                .name("wait-condition")
                .type(OperationSubType.WAIT_FOR_CONDITION.getOperationType())
                .subType(OperationSubType.WAIT_FOR_CONDITION.getValue())
                .status(OperationStatus.FAILED)
                .stepDetails(StepDetails.builder()
                        .attempt(3)
                        .result("retained-state")
                        .build())
                .build();
        var testOperation = new TestOperation(operation, List.of(), serDes, new SerDesRunner(null), EXECUTION_ARN);

        assertEquals("retained-state", testOperation.getStepResult(String.class));
        assertEquals(2, observedContext.get().attempt());
        assertEquals("operation/wait-id/state/attempt-2", observedContext.get().entityId());
        assertNull(observedContext.get().originalValue());
    }
}
