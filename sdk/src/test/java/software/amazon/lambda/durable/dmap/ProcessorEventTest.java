// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.dmap;

import static org.junit.jupiter.api.Assertions.*;

import com.amazonaws.services.lambda.runtime.serialization.events.LambdaEventSerializers;
import com.amazonaws.services.lambda.runtime.serialization.factories.JacksonFactory;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Unit tests for {@link ProcessorEvent}. */
class ProcessorEventTest {
    private static final String RUN_ARN = "arn:aws:lambda:us-east-1:123456789012:distributed-map-run:run-1";

    private static ProcessorRecord rec(String itemId, String body) {
        return new ProcessorRecord(itemId, body);
    }

    private static ProcessorEvent processorEvent(ProcessorRecord... records) {
        return new ProcessorEvent(RUN_ARN, List.of(records));
    }

    // Deserializes the way the Lambda Java runtime does for a RequestHandler whose input type is a POJO outside the
    // runtime's supported event classes: JacksonFactory.getSerializer(Type), then fromJson(InputStream).
    private static Object deserializeAsLambdaRuntime(String json, Class<?> type) {
        assertFalse(LambdaEventSerializers.isLambdaSupportedEvent(type.getName()));
        var serializer = JacksonFactory.getInstance().getSerializer((Type) type);
        return serializer.fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void processorEventWithoutRunArnIsRejected(String runArn) {
        var exception = assertThrows(IllegalStateException.class, () -> new ProcessorEvent(runArn, List.of()));
        assertEquals(
                "expected a distributed map processor event to have a 'distributedMapRunArn'", exception.getMessage());
    }

    @Test
    void processorEventJsonCarriesTheRunArn() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"records\":[{\"itemId\":\"a\",\"body\":\"1\"}]}";

        var event = new JacksonSerDes().deserialize(json, TypeToken.get(ProcessorEvent.class));

        assertEquals(processorEvent(rec("a", "1")), event);
    }

    @Test
    void lambdaRuntimeReadsTheRunArnIntoProcessorEvent() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"records\":[{\"itemId\":\"a\",\"body\":\"1\"}]}";

        var event = deserializeAsLambdaRuntime(json, ProcessorEvent.class);

        assertEquals(processorEvent(rec("a", "1")), event);
    }

    @Test
    void processorEventRejectsMissingRecords() {
        var ex = assertThrows(IllegalStateException.class, () -> new ProcessorEvent(RUN_ARN, null));
        assertTrue(ex.getMessage().contains("'records' array"));
        assertTrue(ex.getMessage().contains("not invoked directly"));
    }

    @Test
    void processorEventRejectsNullRecord() {
        var records = new java.util.ArrayList<ProcessorRecord>();
        records.add(null);

        var exception = assertThrows(IllegalStateException.class, () -> new ProcessorEvent(RUN_ARN, records));
        assertEquals("expected each distributed map processor record to be an object", exception.getMessage());
    }

    @Test
    void lambdaRuntimeRejectsProcessorRecordWithoutBody() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"records\":[{\"itemId\":\"a\"}]}";

        var exception =
                assertThrows(UncheckedIOException.class, () -> deserializeAsLambdaRuntime(json, ProcessorEvent.class));

        var constructorFailure = exception.getCause().getCause();
        assertInstanceOf(IllegalStateException.class, constructorFailure);
        assertEquals(
                "expected each distributed map processor record to have a 'body'", constructorFailure.getMessage());
    }

    @Test
    void lambdaRuntimeRejectsNullProcessorRecord() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"records\":[null]}";

        var exception =
                assertThrows(UncheckedIOException.class, () -> deserializeAsLambdaRuntime(json, ProcessorEvent.class));

        var constructorFailure = exception.getCause().getCause();
        assertInstanceOf(IllegalStateException.class, constructorFailure);
        assertEquals("expected each distributed map processor record to be an object", constructorFailure.getMessage());
    }
}
