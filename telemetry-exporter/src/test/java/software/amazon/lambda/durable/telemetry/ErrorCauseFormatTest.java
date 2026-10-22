// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * The shared error shape {@code {name, message, cause?}}: keys in that order, {@code cause} from
 * {@link Throwable#getCause()} cut after 16 levels and stopped at an already-visited cause, no cause for an error
 * rebuilt from a checkpointed {@link ErrorObject}. Java's {@code getCause()} always returns a {@link Throwable}, so the
 * non-error cause rule of the shared format does not apply to this SDK.
 */
class ErrorCauseFormatTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ARN =
            "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    static final class PaymentError extends RuntimeException {
        PaymentError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A throwable whose cause is set after construction, so tests can build cycles {@code getCause()} returns. */
    static final class LoopingError extends RuntimeException {
        Throwable next;

        LoopingError(String message) {
            super(message);
        }

        @Override
        public synchronized Throwable getCause() {
            return next;
        }
    }

    private static final class CapturingExporter implements TelemetryExporter {
        final List<DurableExecutionTelemetryRecord> records = new ArrayList<>();

        @Override
        public void export(DurableExecutionTelemetryRecord record) {
            records.add(record);
        }
    }

    private static Map<String, OperationChangeItemInfo> failedOp(Throwable opError) {
        Map<String, OperationChangeItemInfo> m = new LinkedHashMap<>();
        m.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1",
                        "charge",
                        "STEP",
                        "Step",
                        null,
                        START,
                        START.plusMillis(5),
                        OperationStatus.FAILED,
                        1,
                        false,
                        opError,
                        null));
        return m;
    }

    private static DurableExecutionTelemetryRecord run(boolean includeErrors, Throwable opError, Throwable execError) {
        var exporter = new CapturingExporter();
        var plugin = Executions.plugin(
                DurableExecutionTelemetry.durableExecutionTelemetry(DurableExecutionTelemetryConfig.builder()
                        .content(ContentConfig.builder()
                                .includeErrors(includeErrors)
                                .build())
                        .addExporter(exporter)
                        .build()),
                ARN,
                START);
        plugin.onInvocationEnd(new InvocationEndInfo(
                "req", ARN, true, START, failedOp(opError), InvocationStatus.FAILED, execError, "in", null));
        assertEquals(1, exporter.records.size());
        return exporter.records.get(0);
    }

    /** Builds a chain of {@code causes} live causes below a top-level error. */
    private static Throwable chain(int causes) {
        Throwable t = null;
        for (int i = causes; i >= 1; i--) {
            t = new IllegalStateException("level " + i, t);
        }
        return new PaymentError("card declined", t);
    }

    private static int depth(ErrorInfo e) {
        int d = 0;
        for (ErrorInfo c = e.cause(); c != null; c = c.cause()) {
            d++;
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws Exception {
        return MAPPER.readValue(json, Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shapeAndKeyOrderInEveryErrorLocation() throws Exception {
        var rec = run(true, chain(1), chain(1));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", "PaymentError");
        expected.put("message", "card declined");
        Map<String, Object> cause = new LinkedHashMap<>();
        cause.put("name", "IllegalStateException");
        cause.put("message", "level 1");
        expected.put("cause", cause);

        Map<String, Object> array = parse(Json.stringify(rec.toWireMap()));
        Map<String, Object> execError = (Map<String, Object>) array.get("error");
        Map<String, Object> opError = (Map<String, Object>)
                ((List<Map<String, Object>>) array.get("operations")).get(0).get("error");
        Map<String, Object> byName = parse(Json.stringify(rec.toByNameWireMap()));
        Map<String, Object> byNameError =
                (Map<String, Object>) ((Map<String, Map<String, Object>>) byName.get("operationsByName"))
                        .get("charge")
                        .get("error");

        for (Map<String, Object> error : List.of(execError, opError, byNameError)) {
            assertEquals(expected, error);
            assertEquals(List.of("name", "message", "cause"), List.copyOf(error.keySet()));
            assertEquals(List.of("name", "message"), List.copyOf(((Map<String, Object>) error.get("cause")).keySet()));
        }
        // The serialized text keeps the order too (Jackson parses into a LinkedHashMap, but check the bytes).
        assertTrue(Json.stringify(rec.toByNameWireMap())
                .contains("\"error\":{\"name\":\"PaymentError\",\"message\":\"card declined\","
                        + "\"cause\":{\"name\":\"IllegalStateException\",\"message\":\"level 1\"}}"));
    }

    @Test
    void errorWithoutCauseOmitsTheKey() {
        var rec = run(true, new PaymentError("card declined", null), new PaymentError("boom", null));
        assertEquals(
                List.of("name", "message"), List.copyOf(rec.error.toWireMap().keySet()));
        assertNull(rec.operations().get(0).error().cause());
    }

    @Test
    void causeChainDepthOneSixteenAndSeventeen() {
        assertEquals(1, depth(DurableExecutionTelemetry.toErrorInfo(chain(1))));
        ErrorInfo sixteen = DurableExecutionTelemetry.toErrorInfo(chain(16));
        assertEquals(16, depth(sixteen));
        ErrorInfo seventeen = DurableExecutionTelemetry.toErrorInfo(chain(17));
        assertEquals(16, depth(seventeen), "chain cut after 16 levels");
        ErrorInfo last = seventeen;
        while (last.cause() != null) {
            last = last.cause();
        }
        assertEquals("level 16", last.message());
    }

    @Test
    void cycleStopsAtAnAlreadyVisitedCause() {
        LoopingError a = new LoopingError("a");
        LoopingError b = new LoopingError("b");
        a.next = b;
        b.next = a;
        ErrorInfo info = DurableExecutionTelemetry.toErrorInfo(a);
        assertEquals("a", info.message());
        assertEquals("b", info.cause().message());
        assertNull(info.cause().cause(), "a was already visited");

        LoopingError self = new LoopingError("self");
        self.next = self;
        assertNull(DurableExecutionTelemetry.toErrorInfo(self).cause());
    }

    @Test
    void namesAreUnchanged() {
        ErrorInfo info = DurableExecutionTelemetry.toErrorInfo(chain(1));
        assertEquals("PaymentError", info.name());
        assertEquals("IllegalStateException", info.cause().name());
    }

    @Test
    void checkpointedOperationErrorHasNoCause() {
        ErrorObject error = ErrorObject.builder()
                .errorType("PaymentError")
                .errorMessage("card declined")
                .build();
        var opError =
                new DurableOperationException(Operation.builder().id("op-1").build(), error);
        var rec = run(true, opError, opError);
        ErrorInfo op = rec.operations().get(0).error();
        assertEquals("PaymentError", op.name());
        assertEquals("card declined", op.message());
        assertNull(op.cause());
        assertNull(rec.error.cause());
        assertFalse(Json.stringify(rec.toByNameWireMap()).contains("\"cause\""));
    }

    @Test
    void includeErrorsFalseRemovesErrorsAndTheirCauses() {
        var rec = run(false, chain(3), chain(3));
        assertNull(rec.error);
        assertNull(rec.operations().get(0).error());
        String array = Json.stringify(rec.toWireMap());
        String byName = Json.stringify(rec.toByNameWireMap());
        for (String json : List.of(array, byName)) {
            assertFalse(json.contains("card declined"), json);
            assertFalse(json.contains("level 1"), json);
            assertFalse(json.contains("\"error\""), json);
        }
    }

    @Test
    void truncationKeepsTheCauseWithItsError() {
        var rec = run(true, chain(2), chain(2));
        rec.input = "x".repeat(10_000);
        var out = Truncation.truncateRecord(rec, 2_000, DurableExecutionTelemetryRecord::toByNameWireMap);
        assertEquals(Boolean.TRUE, out.truncated);
        assertNotNull(out.error);
        assertEquals(2, depth(out.error));
        assertTrue(Json.stringify(out.toByNameWireMap()).contains("\"message\":\"level 2\""));
    }
}
