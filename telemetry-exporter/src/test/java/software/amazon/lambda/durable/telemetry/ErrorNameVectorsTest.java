// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.exception.DurableOperationException;

/**
 * Table A of the cross-SDK {@code error.name} rule. The vectors are loaded from {@code error-name-vectors.json}, which
 * has the same content in the JS, Python and Java repositories; edit it in all three or not at all.
 */
class ErrorNameVectorsTest {

    private static final String VECTORS = "/error-name-vectors.json";

    static Stream<Arguments> reduceVectors() throws IOException {
        List<Arguments> out = new ArrayList<>();
        for (JsonNode v : load().get("reduce")) {
            JsonNode input = v.get("input");
            out.add(Arguments.of(
                    v.get("id").asText(),
                    input.isNull() ? null : input.asText(),
                    v.get("expected").asText()));
        }
        return out.stream();
    }

    private static JsonNode load() throws IOException {
        try (InputStream in = ErrorNameVectorsTest.class.getResourceAsStream(VECTORS)) {
            assertNotNull(in, VECTORS + " on the test classpath");
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void vectorFileHasTheSharedShape() throws IOException {
        JsonNode root = load();
        assertEquals(2, root.get("version").asInt(), "spec version 2 vectors");
        assertTrue(root.get("reduce").size() >= 20, "all Table A rows present, A1 to A17");
    }

    @ParameterizedTest(name = "{0}: [{1}] -> [{2}]")
    @MethodSource("reduceVectors")
    void reducesTypeString(String id, String input, String expected) {
        assertEquals(expected, DurableExecutionTelemetry.simpleTypeName(input), id);
    }

    /**
     * The same vectors through the record path: a checkpointed operation error whose {@code errorType} is the input.
     * The operation type is one with no SDK failure class of its own, so a blank type falls through to Table A.
     *
     * <p>A12 and A13 hold on this path only for operation types without a failure class. For a STEP, CALLBACK,
     * CHAINED_INVOKE or CONTEXT operation, a blank type reports the SDK failure class the core throws for it instead,
     * for example {@code StepFailedException} or {@code CallbackFailedException} (Table B, B13), so the operation and
     * the execution agree.
     */
    @ParameterizedTest(name = "{0}: checkpointed [{1}] -> [{2}]")
    @MethodSource("reduceVectors")
    void checkpointedErrorTypeUsesTheSameReduction(String id, String input, String expected) {
        var op = Operation.builder()
                .id("op-1")
                .type(OperationType.WAIT)
                .status(OperationStatus.FAILED)
                .build();
        var error = ErrorObject.builder()
                .errorType(input)
                .errorMessage("card declined")
                .build();
        var info = DurableExecutionTelemetry.toErrorInfo(new DurableOperationException(op, error));
        assertEquals(expected, info.name(), id);
        assertEquals("card declined", info.message(), "message unchanged");
    }
}
