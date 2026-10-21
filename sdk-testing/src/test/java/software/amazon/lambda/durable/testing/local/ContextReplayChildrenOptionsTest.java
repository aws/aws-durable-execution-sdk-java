// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.testing.local;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.ContextOptions;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

class ContextReplayChildrenOptionsTest {
    @ParameterizedTest
    @ValueSource(strings = {"absent", "false", "true"})
    void startDoesNotRecordReplayChildren(String option) {
        var started = OperationProcessor.applyUpdate(update(OperationAction.START, option), null);
        assertNull(started.contextDetails().replayChildren());
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCEED,absent,false",
        "SUCCEED,false,false",
        "SUCCEED,true,true",
        "FAIL,absent,false",
        "FAIL,false,false",
        "FAIL,true,true"
    })
    void closingUpdateAloneDeterminesReplayChildren(OperationAction action, String option, boolean expected) {
        var started = OperationProcessor.applyUpdate(update(OperationAction.START, "true"), null);
        var closed = OperationProcessor.applyUpdate(update(action, option), started);
        assertEquals(expected, closed.contextDetails().replayChildren());
    }

    private static OperationUpdate update(OperationAction action, String option) {
        var builder = OperationUpdate.builder()
                .id("context")
                .type(OperationType.CONTEXT)
                .action(action);
        if (!option.equals("absent"))
            builder.contextOptions(ContextOptions.builder()
                    .replayChildren(Boolean.parseBoolean(option))
                    .build());
        return builder.build();
    }
}
