// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.serde.FileSystemSerDes;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.RetrySerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.serde.SerDesContext;
import software.amazon.lambda.durable.serde.SerDesPayloadKind;
import software.amazon.lambda.durable.serde.SerDesRunner;
import software.amazon.lambda.durable.serde.SerDesStage;

class CloudDurableTestRunnerTest {

    @Test
    void testConfiguration() {
        var mockClient = mock(LambdaClient.class);
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withPollInterval(Duration.ofSeconds(5))
                .withInvocationType(InvocationType.EVENT);

        assertNotNull(runner);
    }

    @Test
    void testPlaceholderMethods() {
        var mockClient = mock(LambdaClient.class);
        var runner = CloudDurableTestRunner.create(
                "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient);

        assertThrows(IllegalStateException.class, () -> runner.getOperation("test"));
    }

    @Test
    void explicitComposableInputSerDesUsesTheCompletePipeline() {
        var mockClient = mock(LambdaClient.class);
        when(mockClient.invoke(any(InvokeRequest.class)))
                .thenReturn(InvokeResponse.builder()
                        .durableExecutionArn("arn:aws:lambda:us-east-2:123:function:test:1/durable-execution/e/i")
                        .build());
        var wrappingStage = wrappingStage();
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withInputSerDes(new JacksonSerDes().then(wrappingStage));

        runner.startAsync("value");

        var request = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(mockClient).invoke(request.capture());
        assertEquals("<\"value\">", request.getValue().payload().asUtf8String());
    }

    @Test
    void persistedComposableSerDesUsesCompletePipelineForDefaultInput() {
        var mockClient = mock(LambdaClient.class);
        when(mockClient.invoke(any(InvokeRequest.class)))
                .thenReturn(InvokeResponse.builder()
                        .durableExecutionArn("arn:aws:lambda:us-east-2:123:function:test:1/durable-execution/e/i")
                        .build());
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withSerDes(new JacksonSerDes().then(wrappingStage()));

        runner.startAsync("value");

        var request = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(mockClient).invoke(request.capture());
        assertEquals("<\"value\">", request.getValue().payload().asUtf8String());
    }

    @Test
    void contextDependentPersistedSerDesRequiresExplicitInputSerDes() {
        var mockClient = mock(LambdaClient.class);
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withSerDes(new JacksonSerDes().then(contextDependentStage()));

        var failure = assertThrows(RuntimeException.class, () -> runner.startAsync("value"));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("withInputSerDes"));
        verifyNoInteractions(mockClient);
    }

    @Test
    void explicitInputSerDesMustBeContextFree() {
        var mockClient = mock(LambdaClient.class);
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withInputSerDes(contextDependentStage());

        var failure = assertThrows(RuntimeException.class, () -> runner.startAsync("value"));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("Initial input SerDes"));
        verifyNoInteractions(mockClient);
    }

    @Test
    void contextDependentPersistedSerDesRequiresValueCodecInput() {
        var mockClient = mock(LambdaClient.class);
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withSerDes(new JacksonSerDes().then(contextDependentStage()))
                .withInputSerDes(new JacksonSerDes().then(wrappingStage()));

        var failure = assertThrows(RuntimeException.class, () -> runner.startAsync("value"));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("must use a value codec"));
        verifyNoInteractions(mockClient);
    }

    @Test
    void contextDependentPersistedSerDesRejectsRetryWrappedComposableInput() {
        var mockClient = mock(LambdaClient.class);
        var inputSerDes = new RetrySerDes(new JacksonSerDes().then(wrappingStage()), RetryStrategies.Presets.NO_RETRY);
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withSerDes(new JacksonSerDes().then(contextDependentStage()))
                .withInputSerDes(inputSerDes);

        var failure = assertThrows(RuntimeException.class, () -> runner.startAsync("value"));

        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(failure.getCause().getMessage().contains("must use a value codec"));
        verifyNoInteractions(mockClient);
    }

    @Test
    void valueCodecInputRoundTripsThroughFileSystemPipeline(@TempDir Path basePath) {
        var mockClient = mock(LambdaClient.class);
        var executionArn = "arn:aws:lambda:us-east-2:123:function:test:1/durable-execution/e/i";
        when(mockClient.invoke(any(InvokeRequest.class)))
                .thenReturn(InvokeResponse.builder()
                        .durableExecutionArn(executionArn)
                        .build());
        var persistedSerDes =
                new JacksonSerDes().then(FileSystemSerDes.stageBuilder(basePath).build());
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withSerDes(persistedSerDes)
                .withInputSerDes(new JacksonSerDes());

        runner.startAsync("value");

        var request = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(mockClient).invoke(request.capture());
        assertEquals(
                "value",
                new SerDesRunner(null)
                        .deserialize(
                                persistedSerDes,
                                request.getValue().payload().asUtf8String(),
                                TypeToken.get(String.class),
                                SerDesContext.forExecution(executionArn, "i", "execution", SerDesPayloadKind.INPUT)));
    }

    @Test
    void replacingPersistedSerDesPreservesExplicitInputSerDes() {
        var mockClient = mock(LambdaClient.class);
        when(mockClient.invoke(any(InvokeRequest.class)))
                .thenReturn(InvokeResponse.builder()
                        .durableExecutionArn("arn:aws:lambda:us-east-2:123:function:test:1/durable-execution/e/i")
                        .build());
        var runner = CloudDurableTestRunner.create(
                        "arn:aws:lambda:us-east-2:123:function:test", String.class, String.class, mockClient)
                .withInputSerDes(new JacksonSerDes().then(wrappingStage()))
                .withSerDes(new JacksonSerDes());

        runner.startAsync("value");

        var request = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(mockClient).invoke(request.capture());
        assertEquals("<\"value\">", request.getValue().payload().asUtf8String());
    }

    private static SerDesStage wrappingStage() {
        return new SerDesStage() {
            @Override
            public String serialize(String value) {
                return "<" + value + ">";
            }

            @Override
            public String deserialize(String data) {
                return data.substring(1, data.length() - 1);
            }
        };
    }

    private static ContextDependentSerDesStage contextDependentStage() {
        return new ContextDependentSerDesStage();
    }

    private static final class ContextDependentSerDesStage implements SerDes, SerDesStage {
        @Override
        public String serialize(Object value) {
            return value.toString();
        }

        @Override
        public String serialize(String value) {
            return value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T deserialize(String data, TypeToken<T> typeToken) {
            return (T) data;
        }

        @Override
        public String deserialize(String data) {
            return data;
        }

        @Override
        public boolean requiresDurableContext() {
            return true;
        }
    }
}
