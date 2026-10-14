// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPlugin;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.OperationEndInfo;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class PluginErrorContainerCompatibilityTest {
    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of("absent", null),
                Arguments.of("empty", ErrorObject.builder().build()),
                Arguments.of(
                        "empty-message", ErrorObject.builder().errorMessage("").build()),
                Arguments.of(
                        "message",
                        ErrorObject.builder().errorMessage("external failure").build()),
                Arguments.of(
                        "empty-stack",
                        ErrorObject.builder().stackTrace(List.of()).build()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("errors")
    void nonOtelPluginRetainsFailureContainersAcrossCallbackResumeAndReplay(String label, ErrorObject error) {
        var starts = new CopyOnWriteArrayList<InvocationInfo>();
        var ends = new CopyOnWriteArrayList<InvocationEndInfo>();
        var operationEnds = new CopyOnWriteArrayList<OperationEndInfo>();
        var listener = new DurableExecutionPlugin() {
            public void onInvocationStart(InvocationInfo info) {
                starts.add(info);
            }

            public void onInvocationEnd(InvocationEndInfo info) {
                ends.add(info);
            }

            public void onOperationEnd(OperationEndInfo info) {
                operationEnds.add(info);
            }
        };
        var submissions = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.waitForCallback(
                        "listener-error", String.class, (callbackId, step) -> submissions.incrementAndGet()),
                DurableConfig.builder().withPlugins(listener).build());
        assertEquals(ExecutionStatus.PENDING, runner.run("input").getStatus());
        var callbackName = "listener-error-callback";
        runner.failCallback(runner.getCallbackId(callbackName), error);
        var failed = runner.run("input");
        assertEquals(ExecutionStatus.FAILED, failed.getStatus());
        var operation = failed.getOperation(callbackName);
        assertEquals(error, operation.getCallbackDetails().error());
        assertEquals(error, failed.getError().orElse(null));
        assertContainer(error, starts.get(1).operations().get(operation.getId()).error());
        assertContainer(
                error, starts.get(1).updatedOperations().get(operation.getId()).error());
        assertContainer(error, ends.get(1).operations().get(operation.getId()).error());
        var callbacks = operationEnds.stream()
                .filter(info -> callbackName.equals(info.name()))
                .toList();
        assertEquals(1, callbacks.size());
        assertEquals("FAILED", callbacks.get(0).status());
        assertContainer(error, callbacks.get(0).error());
        var replay = runner.run("input");
        assertEquals(ExecutionStatus.FAILED, replay.getStatus());
        assertEquals(failed.getError(), replay.getError());
        // The completed failed context omits its children from replay input. Inspection still retains the raw
        // callback failure, and all of the delivered-container/identity checks above remain required.
        assertFalse(starts.get(2).operations().containsKey(operation.getId()));
        assertEquals(
                error, replay.getOperation(callbackName).getCallbackDetails().error());
        assertEquals(1, submissions.get(), "Completed submission must not execute again");
        assertEquals(
                1,
                operationEnds.stream()
                        .filter(info -> callbackName.equals(info.name()))
                        .count());
    }

    private static void assertContainer(ErrorObject expected, Throwable actual) {
        if (expected == null) {
            assertNull(actual, "An absent container retains the existing null metadata behavior");
        } else {
            var failure = assertInstanceOf(DurableOperationException.class, actual);
            assertEquals(expected, failure.getErrorObject(), "Plugins retain the original failure container");
        }
    }
}
