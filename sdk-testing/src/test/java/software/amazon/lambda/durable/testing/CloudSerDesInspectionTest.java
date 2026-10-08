// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.Event;
import software.amazon.awssdk.services.lambda.model.EventResult;
import software.amazon.awssdk.services.lambda.model.EventType;
import software.amazon.awssdk.services.lambda.model.ExecutionSucceededDetails;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionHistoryRequest;
import software.amazon.awssdk.services.lambda.model.GetDurableExecutionHistoryResponse;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.StepSucceededDetails;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.FileSystemStorageMode;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDesContext;

class CloudSerDesInspectionTest {
    private static final String FUNCTION_ARN = "arn:aws:lambda:us-east-1:123456789012:function:test:1";
    private static final String EXECUTION_ARN = FUNCTION_ARN + "/durable-execution/name/invocation";
    private static final String OPERATION_ID = "nested-step-id";
    private static final SerDesContext CONTEXT = context(EXECUTION_ARN);
    private static final TypeToken<List<String>> LIST = new TypeToken<>() {};

    @TempDir
    Path directory;

    @ParameterizedTest
    @EnumSource(FileSystemStorageMode.class)
    void syncCloudInspectionUsesOperationSerializerAndExecutionArn(FileSystemStorageMode mode) {
        var contexts = new ArrayList<SerDesContext>();
        var files = files(mode, contexts);
        var envelope = files.serialize(List.of("value"), CONTEXT);
        var client = client(EXECUTION_ARN, history(envelope));
        var runner = CloudDurableTestRunner.create(FUNCTION_ARN, String.class, String.class, client);

        var result = runner.run("input");
        assertEquals("handler output", result.getResult(String.class));
        assertEquals(List.of("value"), result.getOperation("offloaded").getStepResult(LIST, files));
        assertEquals(List.of("value"), runner.getOperation("offloaded").getStepResult(List.class, files));
        assertEquals(List.of(CONTEXT, CONTEXT), contexts);
        assertEquals(envelope, result.getOperation("offloaded").getStepDetails().result());
        assertRequest(client, InvocationType.REQUEST_RESPONSE);
    }

    @ParameterizedTest
    @EnumSource(FileSystemStorageMode.class)
    void asyncCloudSnapshotsUseTheSameContextAsSyncResults(FileSystemStorageMode mode) {
        var contexts = new ArrayList<SerDesContext>();
        var files = files(mode, contexts);
        var envelope = files.serialize(List.of("value"), CONTEXT);
        var client = client(EXECUTION_ARN, history(envelope));
        var runner = CloudDurableTestRunner.create(FUNCTION_ARN, String.class, String.class, client);

        var execution = runner.startAsync("input");
        var result = execution.pollUntilComplete();
        assertEquals("handler output", result.getResult(String.class));
        assertEquals(List.of("value"), execution.getOperation("offloaded").getStepResult(LIST, files));
        assertEquals(List.of("value"), result.getOperation("offloaded").getStepResult(List.class, files));
        assertEquals(List.of(CONTEXT, CONTEXT), contexts);
        assertRequest(client, InvocationType.EVENT);
    }

    @Test
    void snapshotsKeepTheirOwnExecutionIdentityWhenRunnerIsReused() {
        var contexts = new ArrayList<SerDesContext>();
        var files = files(FileSystemStorageMode.ALWAYS, contexts);
        var secondArn = EXECUTION_ARN + "-second";
        var firstEnvelope = files.serialize("first", CONTEXT);
        var secondEnvelope = files.serialize("second", context(secondArn));
        var client = client(EXECUTION_ARN, history(firstEnvelope));
        when(client.invoke(any(InvokeRequest.class))).thenReturn(response(EXECUTION_ARN), response(secondArn));
        when(client.getDurableExecutionHistory(any(GetDurableExecutionHistoryRequest.class)))
                .thenReturn(history(firstEnvelope), history(secondEnvelope));
        var runner = CloudDurableTestRunner.create(FUNCTION_ARN, String.class, String.class, client);

        var first = runner.run("input");
        var second = runner.run("input");
        assertEquals("first", first.getOperation("offloaded").getStepResult(String.class, files));
        assertEquals("second", second.getOperation("offloaded").getStepResult(String.class, files));
        assertEquals(List.of(CONTEXT, context(secondArn)), contexts);
    }

    @Test
    void cloudResultCollectionDoesNotReadFilesUntilExplicitInspection() throws Exception {
        var files = FileSystemSerDes.builder(directory).build();
        var envelope = files.serialize("value", CONTEXT);
        var pointer = new JacksonSerDes().deserialize(envelope, new TypeToken<Map<String, String>>() {});
        Files.delete(Path.of(pointer.get("file")));
        var client = client(EXECUTION_ARN, history(envelope));
        var runner = CloudDurableTestRunner.create(FUNCTION_ARN, String.class, String.class, client);

        var result = runner.run("input");
        assertEquals("handler output", result.getResult(String.class));
        assertEquals(pointer, result.getOperation("offloaded").getStepResult(Map.class));
        assertEquals(envelope, result.getOperation("offloaded").getStepDetails().result());
        assertThrows(
                SerDesException.class, () -> result.getOperation("offloaded").getStepResult(String.class, files));
    }

    private FileSystemSerDes files(FileSystemStorageMode mode, List<SerDesContext> contexts) {
        var delegate = new JacksonSerDes() {
            @Override
            public <T> T deserialize(String data, TypeToken<T> type, SerDesContext context) {
                contexts.add(context);
                return super.deserialize(data, type);
            }
        };
        return FileSystemSerDes.builder(directory)
                .storageMode(mode)
                .delegate(delegate)
                .build();
    }

    private static LambdaClient client(String arn, GetDurableExecutionHistoryResponse history) {
        var client = mock(LambdaClient.class);
        when(client.invoke(any(InvokeRequest.class))).thenReturn(response(arn));
        when(client.getDurableExecutionHistory(any(GetDurableExecutionHistoryRequest.class)))
                .thenReturn(history);
        return client;
    }

    private static InvokeResponse response(String arn) {
        return InvokeResponse.builder().statusCode(200).durableExecutionArn(arn).build();
    }

    private static GetDurableExecutionHistoryResponse history(String payload) {
        var step = Event.builder()
                .id(OPERATION_ID)
                .name("offloaded")
                .eventType(EventType.STEP_SUCCEEDED)
                .eventTimestamp(Instant.now())
                .stepSucceededDetails(StepSucceededDetails.builder()
                        .result(EventResult.builder().payload(payload).build())
                        .build())
                .build();
        var completed = Event.builder()
                .id("execution")
                .eventType(EventType.EXECUTION_SUCCEEDED)
                .eventTimestamp(Instant.now())
                .executionSucceededDetails(ExecutionSucceededDetails.builder()
                        .result(EventResult.builder()
                                .payload("\"handler output\"")
                                .build())
                        .build())
                .build();
        return GetDurableExecutionHistoryResponse.builder()
                .events(step, completed)
                .build();
    }

    private static SerDesContext context(String arn) {
        return new SerDesContext(arn, "operation/" + OPERATION_ID + "/result");
    }

    private static void assertRequest(LambdaClient client, InvocationType type) {
        var request = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(client).invoke(request.capture());
        assertEquals("\"input\"", request.getValue().payload().asUtf8String());
        assertEquals(type, request.getValue().invocationType());
    }
}
