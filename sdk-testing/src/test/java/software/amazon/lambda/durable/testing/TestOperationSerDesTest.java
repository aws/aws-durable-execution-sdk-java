// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;

class TestOperationSerDesTest {
    private static final String ARN = "arn:aws:lambda:us-east-1:123456789012:function:test:1/durable-execution/name/id";
    private static final String OPERATION_ID = "child-step";
    private static final SerDesContext CONTEXT = new SerDesContext(ARN, "operation/" + OPERATION_ID + "/result");

    @TempDir
    Path directory;

    @Test
    void explicitSerializerDecodesGenericPayloadWithoutChangingDefaultOrRawDetails() {
        var files = FileSystemSerDes.builder(directory).build();
        var value = Map.of("items", List.of("first", "second"));
        var envelope = files.serialize(value, CONTEXT);
        var operation = operation(envelope, new JacksonSerDes(), ARN);

        assertTrue(operation.getStepResult(Map.class).containsKey("file"));
        assertEquals(value, operation.getStepResult(new TypeToken<Map<String, List<String>>>() {}, files));
        assertEquals(envelope, operation.getStepDetails().result());
        assertTrue(operation.getStepResult(Map.class).containsKey("file"));
    }

    @Test
    void bothDefaultAndOverrideReceiveTheOperationContext() {
        var seen = new AtomicReference<SerDesContext>();
        var contextual = new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> type, SerDesContext context) {
                seen.set(context);
                return super.deserialize(data, type);
            }
        };
        var operation = operation("\"value\"", contextual, ARN);
        assertEquals("value", operation.getStepResult(String.class));
        assertEquals(CONTEXT, seen.getAndSet(null));
        assertEquals("value", operation.getStepResult(TypeToken.get(String.class), contextual));
        assertEquals(CONTEXT, seen.get());
    }

    @Test
    void existingConstructorsKeepContextFreeSerializerBehavior() {
        var serializer = mock(SerDes.class);
        var type = TypeToken.get(String.class);
        when(serializer.deserialize("encoded", type)).thenReturn("value");
        var stored = stored("encoded");

        assertEquals("value", new TestOperation(stored, serializer).getStepResult(type));
        assertEquals("value", new TestOperation(stored, List.of(), serializer).getStepResult(type));
        verify(serializer, times(2)).deserialize("encoded", type);
        verifyNoMoreInteractions(serializer);
    }

    @Test
    void missingStepOrPayloadDoesNotCallSerializer() {
        var serializer = mock(SerDes.class);
        assertNull(operation(null, new JacksonSerDes(), ARN).getStepResult(String.class, serializer));
        var wait =
                new TestOperation(Operation.builder().type(OperationType.WAIT).build(), List.of(), serializer, ARN);
        assertNull(wait.getStepResult(String.class, serializer));
        verifyNoInteractions(serializer);
    }

    private static TestOperation operation(String payload, SerDes serializer, String arn) {
        return new TestOperation(stored(payload), List.of(), serializer, arn);
    }

    private static Operation stored(String payload) {
        return Operation.builder()
                .id(OPERATION_ID)
                .type(OperationType.STEP)
                .stepDetails(StepDetails.builder().result(payload).build())
                .build();
    }
}
