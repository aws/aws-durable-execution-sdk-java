// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.dag.DagResult;
import software.amazon.lambda.durable.dag.internal.DagResultSerDes;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionContextConfig;
import software.amazon.lambda.durable.extension.ExtensionContextFunction;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.extension.ExtensionStepResult;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.serde.JacksonSerDes;

class DurableDagOperationTest {
    @Test
    void dagUsesExtensionReservationsForContainerAndTasks() {
        var context = mock(ExtensionContext.class);
        var parent = mock(ExtensionOperation.class);
        var parentFuture = mockDagFuture();
        var serDes = new JacksonSerDes();
        var retryDelay = Duration.ofSeconds(3);
        var stepConfig = StepConfig.builder()
                .serDes(serDes)
                .retryStrategy((error, attempt) -> RetryDecision.retry(retryDelay))
                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .build();
        when(context.getDurableConfig())
                .thenReturn(DurableConfig.builder().withSerDes(serDes).build());
        when(context.reserve("graph")).thenReturn(parent);
        when(parent.runInChildContextAsync(
                        eq("Dag"),
                        eq(TypeToken.get(DagResult.class)),
                        any(ExtensionContextFunction.class),
                        any(ExtensionContextConfig.class)))
                .thenReturn(parentFuture);

        DurableFuture<DagResult> actual;
        try (var ignored = BaseContextImpl.attachCurrentContext(context)) {
            actual = DurableDagOperation.dagAsync(
                    "graph", dag -> dag.step("node", String.class, (deps, step) -> "done", stepConfig));
        }

        assertSame(parentFuture, actual);
        var function = extensionFunction();
        var extensionConfig = ArgumentCaptor.forClass(ExtensionContextConfig.class);
        verify(parent)
                .runInChildContextAsync(
                        eq("Dag"), eq(TypeToken.get(DagResult.class)), function.capture(), extensionConfig.capture());
        assertInstanceOf(DagResultSerDes.class, extensionConfig.getValue().serDes());
        verify(context).reserve("graph");

        var child = mock(ExtensionContext.class);
        var task = mock(ExtensionOperation.class);
        var taskConfig = ArgumentCaptor.forClass(ExtensionStepConfig.class);
        when(child.reserve("node", "DAG_NODE_T_node")).thenReturn(task);
        when(task.stepAsync(
                        eq("Step"),
                        eq(TypeToken.get(String.class)),
                        any(ExtensionStepFunction.class),
                        taskConfig.capture()))
                .thenReturn(new CompletedFuture<>("done"));

        DagResult result;
        try (var ignored = BaseContextImpl.attachCurrentContext(child)) {
            result = function.getValue().apply().result();
        }

        assertEquals("done", result.getResult("node").orElseThrow());
        assertSame(serDes, taskConfig.getValue().serDes());
        assertEquals(
                ExtensionStepConfig.StepSemantics.AT_MOST_ONCE_PER_RETRY,
                taskConfig.getValue().semanticsPerRetry());
        var retryDecision =
                taskConfig.getValue().retryStrategy().makeRetryDecision(new IllegalStateException("retry"), "state", 1);
        var retry = assertInstanceOf(ExtensionStepResult.Retry.class, retryDecision);
        assertEquals("state", retry.state());
        assertEquals(retryDelay, retry.delay());
        verify(child).reserve("node", "DAG_NODE_T_node");
        verify(child, never()).reserve("node");
    }

    @Test
    void invalidNameFailsBeforeRegistration() {
        var context = mock(ExtensionContext.class);
        var registrationCalled = new AtomicBoolean();

        try (var ignored = BaseContextImpl.attachCurrentContext(context)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> DurableDagOperation.dagAsync("", dag -> registrationCalled.set(true)));
        }

        assertEquals(false, registrationCalled.get());
        verify(context, never()).reserve(any());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<ExtensionContextFunction<DagResult>> extensionFunction() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(ExtensionContextFunction.class);
    }

    @SuppressWarnings("unchecked")
    private DurableFuture<DagResult> mockDagFuture() {
        return mock(DurableFuture.class);
    }

    private record CompletedFuture<T>(T result) implements DurableFuture<T> {
        @Override
        public T get() {
            return result;
        }

        @Override
        public CompletableFuture<Void> completionFuture() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
