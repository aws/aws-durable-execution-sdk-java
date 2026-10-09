// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ContextDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.operation.BaseDurableOperation;

class OperationErrorMetadataTest {
    static Stream<Arguments> terminalErrors() {
        return Stream.of(
                        OperationType.STEP, OperationType.CHAINED_INVOKE, OperationType.CALLBACK, OperationType.CONTEXT)
                .flatMap(type -> Stream.of(OperationStatus.FAILED, OperationStatus.TIMED_OUT, OperationStatus.STOPPED)
                        .flatMap(status -> Stream.of(
                                Arguments.of(type, status, "omitted", null, false),
                                Arguments.of(
                                        type,
                                        status,
                                        "empty",
                                        ErrorObject.builder().build(),
                                        false),
                                Arguments.of(
                                        type,
                                        status,
                                        "details",
                                        ErrorObject.builder()
                                                .errorMessage("failure")
                                                .build(),
                                        true))));
    }

    @ParameterizedTest(name = "{0}/{1}/{2}")
    @MethodSource("terminalErrors")
    void snapshotMetadataDoesNotMutateStoredFailure(
            OperationType type, OperationStatus status, String label, ErrorObject error, boolean hasDetails) {
        var operation = operation(type, status, error);
        var saved = operation.toBuilder().build();
        var info = PluginInfoConverter.toOperationItemMap(List.of(operation), Set.of(operation.id()))
                .get(operation.id());
        assertEquals(status, info.status());
        assertTrue(info.isReplay());
        assertNull(info.result());
        if (hasDetails) {
            var failure = assertInstanceOf(DurableOperationException.class, info.error());
            assertSame(error, failure.getErrorObject());
            assertSame(operation, failure.getOperation());
        } else {
            assertNull(info.error(), "an omitted or zero-member error container supplies no error details");
        }
        assertSame(error, BaseDurableOperation.getErrorObject(operation));
        assertEquals(saved, operation, "metadata conversion must not rewrite the operation or its persisted error");
    }

    private static Operation operation(OperationType type, OperationStatus status, ErrorObject error) {
        var builder =
                Operation.builder().id("operation").name("operation").type(type).status(status);
        switch (type) {
            case STEP -> builder.stepDetails(StepDetails.builder().error(error).build());
            case CHAINED_INVOKE ->
                builder.chainedInvokeDetails(
                        ChainedInvokeDetails.builder().error(error).build());
            case CALLBACK ->
                builder.callbackDetails(CallbackDetails.builder().error(error).build());
            case CONTEXT ->
                builder.contextDetails(ContextDetails.builder().error(error).build());
            default -> throw new IllegalArgumentException("Unsupported test operation type: " + type);
        }
        return builder.build();
    }
}
