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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Unit tests for {@link ReaderEvent}. */
class ReaderEventTest {
    private static final String RUN_ARN = "arn:aws:lambda:us-east-1:123456789012:distributed-map-run:run-1";

    private static ReaderEvent readerEvent(String state, int maxItemsPerPage) {
        return new ReaderEvent(RUN_ARN, maxItemsPerPage, state);
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
    void readerEventWithoutRunArnIsRejected(String runArn) {
        var exception = assertThrows(IllegalStateException.class, () -> new ReaderEvent(runArn, 10, null));
        assertEquals(
                "expected a distributed map reader event to have a 'distributedMapRunArn'", exception.getMessage());
    }

    @Test
    void readerEventJsonCarriesTheRunArn() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"maxItemsPerPage\":10,\"state\":\"s\"}";

        var event = new JacksonSerDes().deserialize(json, TypeToken.get(ReaderEvent.class));

        assertEquals(readerEvent("s", 10), event);
    }

    @Test
    void lambdaRuntimeReadsTheRunArnIntoReaderEvent() {
        var json = "{\"distributedMapRunArn\":\"" + RUN_ARN + "\",\"maxItemsPerPage\":10,\"state\":\"s\"}";

        var event = deserializeAsLambdaRuntime(json, ReaderEvent.class);

        assertEquals(readerEvent("s", 10), event);
    }

    @Test
    void lambdaRuntimeRejectsReaderEventWithoutRunArn() {
        var json = "{\"maxItemsPerPage\":10,\"state\":\"s\"}";

        // The serializer wraps Jackson's exception, which wraps the IllegalStateException from the record constructor.
        var exception =
                assertThrows(UncheckedIOException.class, () -> deserializeAsLambdaRuntime(json, ReaderEvent.class));

        var constructorFailure = exception.getCause().getCause();
        assertInstanceOf(IllegalStateException.class, constructorFailure);
        assertEquals(
                "expected a distributed map reader event to have a 'distributedMapRunArn'",
                constructorFailure.getMessage());
    }

    @Test
    void readerEventRejectsMissingMaxItemsPerPage() {
        var ex = assertThrows(IllegalStateException.class, () -> new ReaderEvent(RUN_ARN, null, "state"));
        assertTrue(ex.getMessage().contains("'maxItemsPerPage'"));
    }
}
