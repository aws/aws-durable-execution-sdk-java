// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * The shared error shape {@code {name, message, cause?}}: keys in that order, {@code cause} from
 * {@link Throwable#getCause()} cut after 16 levels and stopped at an already-visited cause, followed behind an SDK
 * wrapper that also carries a checkpointed {@link ErrorObject}, never throwing while a cause is read, and every message
 * capped at 8,192 characters. Java's {@code getCause()} always returns a {@link Throwable}, so the non-error cause rule
 * of the shared format does not arise in this SDK.
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

    /** A throwable whose accessors fail, to check that reading a cause never throws. */
    static final class BrokenError extends RuntimeException {
        final boolean brokenCause;
        final RuntimeException failure;

        BrokenError(boolean brokenCause, RuntimeException failure) {
            super("broken");
            this.brokenCause = brokenCause;
            this.failure = failure;
        }

        @Override
        public synchronized Throwable getCause() {
            if (brokenCause) {
                throw failure;
            }
            return null;
        }

        @Override
        public String getMessage() {
            if (!brokenCause) {
                throw failure;
            }
            return "broken";
        }
    }

    /** A checkpoint continuation failure keeps the ErrorObject identity and the live cause. */
    @Test
    void wrapperWithErrorObjectAndLiveCauseFollowsTheCause() {
        var io = new IOException("disk full", new IllegalStateException("inner"));
        var control = new UnrecoverableDurableExecutionException(
                ErrorObject.builder()
                        .errorType("java.io.IOException")
                        .errorMessage("Error in SDK checkpoint continuation")
                        .build(),
                true,
                io);
        ErrorInfo info = DurableExecutionTelemetry.toErrorInfo(control);
        assertEquals("java.io.IOException", info.name());
        assertEquals("Error in SDK checkpoint continuation", info.message());
        assertEquals("IOException", info.cause().name());
        assertEquals("disk full", info.cause().message());
        assertEquals("IllegalStateException", info.cause().cause().name());
        assertNull(info.cause().cause().cause());

        // A user exception wrapping such a wrapper continues through it.
        ErrorInfo outer = DurableExecutionTelemetry.toErrorInfo(new RuntimeException("outer", control));
        assertEquals("Error in SDK checkpoint continuation", outer.cause().message());
        assertEquals("disk full", outer.cause().cause().message());

        // An operation wrapper with an ErrorObject and a live cause follows it too.
        var opError = new DurableOperationException(
                Operation.builder().id("op-1").build(),
                ErrorObject.builder()
                        .errorType("CallbackError")
                        .errorMessage("cb")
                        .build(),
                "cb",
                io);
        ErrorInfo op = DurableExecutionTelemetry.toErrorInfo(opError);
        assertEquals("CallbackError", op.name());
        assertEquals("disk full", op.cause().message());
    }

    @Test
    void depthAndIdentityGuardsStillApplyBehindAWrapper() {
        LoopingError loop = new LoopingError("loop");
        var control = new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorType("X").errorMessage("m").build(), true, loop);
        loop.next = control;
        ErrorInfo info = DurableExecutionTelemetry.toErrorInfo(control);
        assertEquals("loop", info.cause().message());
        assertNull(info.cause().cause(), "the wrapper was already visited");

        var deep = new UnrecoverableDurableExecutionException(
                ErrorObject.builder().errorType("X").errorMessage("m").build(), true, chain(20));
        assertEquals(16, depth(DurableExecutionTelemetry.toErrorInfo(deep)));
    }

    /** Rule 8: levels are written exactly as getCause() returns them, even when one repeats the level above. */
    @Test
    void repeatedLevelsAreNotSkipped() {
        var inner = new IllegalStateException("same");
        var outer = new IllegalStateException("same", inner);
        ErrorInfo info = DurableExecutionTelemetry.toErrorInfo(outer);
        assertEquals("same", info.message());
        assertEquals("IllegalStateException", info.cause().name());
        assertEquals("same", info.cause().message());
    }

    /** Rule 6: a failure while reading a cause ends the chain there, and the error is still written. */
    @Test
    void readingACauseNeverThrows() {
        // getCause() throws on the top-level error: written without a cause.
        ErrorInfo top = DurableExecutionTelemetry.toErrorInfo(new BrokenError(true, new IllegalStateException("x")));
        assertEquals("broken", top.message());
        assertNull(top.cause());

        // getCause() throws one level down: the chain ends at that level.
        ErrorInfo mid = DurableExecutionTelemetry.toErrorInfo(
                new RuntimeException("outer", new BrokenError(true, new IllegalStateException("x"))));
        assertEquals("broken", mid.cause().message());
        assertNull(mid.cause().cause());

        // A cause whose message cannot be read ends the chain above it.
        ErrorInfo unreadable = DurableExecutionTelemetry.toErrorInfo(
                new RuntimeException("outer", new BrokenError(false, new IllegalStateException("x"))));
        assertEquals("outer", unreadable.message());
        assertNull(unreadable.cause());

        // Through the plugin, the record is kept.
        var rec = run(true, chain(1), new RuntimeException("outer", new BrokenError(false, new RuntimeException())));
        assertEquals("outer", rec.error.message());
        assertNull(rec.error.cause());
    }

    /** A root error whose message cannot be read is written with a null message and its class name; it is kept. */
    @Test
    void rootErrorWithUnreadableMessageIsWrittenWithNullMessage() {
        ErrorInfo root = DurableExecutionTelemetry.toErrorInfo(new BrokenError(false, new IllegalStateException("x")));
        assertEquals("BrokenError", root.name());
        assertNull(root.message());

        // A readable cause under it is still written.
        var withCause = new RuntimeException("unused", new IllegalStateException("inner")) {
            @Override
            public String getMessage() {
                throw new IllegalStateException("unreadable");
            }
        };
        ErrorInfo info = DurableExecutionTelemetry.toErrorInfo(withCause);
        assertNull(info.message());
        assertEquals("inner", info.cause().message());

        // Through the plugin, the record and its error are kept.
        var rec = run(true, chain(1), new BrokenError(false, new RuntimeException()));
        assertEquals("BrokenError", rec.error.name());
        assertNull(rec.error.message());
    }

    @Test
    void fatalErrorWhileReadingACausePropagates() {
        var oom = new OutOfMemoryError("simulated");
        var t = new RuntimeException("outer") {
            @Override
            public synchronized Throwable getCause() {
                throw oom;
            }
        };
        assertSame(oom, assertThrows(OutOfMemoryError.class, () -> DurableExecutionTelemetry.toErrorInfo(t)));
    }

    /** Rule 7: every message, on the error and on each cause, is capped at 8,192 characters plus an ellipsis. */
    @Test
    void everyMessageIsCappedAt8192Characters() {
        String exact = "a".repeat(ErrorInfo.MAX_CHARS);
        String over = "b".repeat(ErrorInfo.MAX_CHARS + 1);
        String huge = "\\".repeat(100_000);
        ErrorInfo info = DurableExecutionTelemetry.toErrorInfo(
                new RuntimeException(huge, new IllegalStateException(over, new IllegalStateException(exact))));
        assertEquals("\\".repeat(8_192) + "\u2026", info.message());
        assertEquals("b".repeat(8_192) + "\u2026", info.cause().message());
        assertEquals(exact, info.cause().cause().message(), "exactly 8,192 characters is not cut");

        // The cap counts code points and never splits a surrogate pair; applying it again changes nothing.
        String emoji = "\uD83D\uDE00".repeat(8_193);
        ErrorInfo e = new ErrorInfo("E", emoji);
        assertEquals("\uD83D\uDE00".repeat(8_192) + "\u2026", e.message());
        assertEquals(e.message(), new ErrorInfo("E", e.message()).message());

        // Operation errors and the ErrorObject message path are capped too.
        var opError = new DurableOperationException(
                Operation.builder().id("op-1").build(),
                ErrorObject.builder().errorType("T").errorMessage(over).build());
        var rec = run(true, opError, opError);
        assertEquals(8_193, rec.operations().get(0).error().message().length());
        assertEquals(8_193, rec.error.message().length());
    }

    /** Size limit step 1: every cause is removed from every error before anything else is dropped. */
    @Test
    @SuppressWarnings("unchecked")
    void truncationDropsEveryCauseFirst() throws Exception {
        var rec = run(true, chain(2), chain(2));
        rec.input = "small";
        int full = Json.byteSize(rec.toByNameWireMap());
        var out = Truncation.truncateRecord(rec, full - 1, DurableExecutionTelemetryRecord::toByNameWireMap);
        assertEquals(Boolean.TRUE, out.truncated);
        assertEquals(Boolean.TRUE, out.droppedCauses);
        assertNull(out.error.cause());
        assertEquals("card declined", out.error.message());
        assertNull(out.operations().get(0).error().cause());
        assertEquals("small", out.input, "dropping causes was enough");
        assertNull(out.droppedOperations);
        assertNotNull(rec.error.cause(), "the input record is not mutated");

        for (String json : List.of(Json.stringify(out.toByNameWireMap()), Json.stringify(out.toWireMap()))) {
            assertFalse(json.contains("\"cause\""), json);
            Map<String, Object> parsed = parse(json);
            List<String> keys = List.copyOf(parsed.keySet());
            assertEquals(Boolean.TRUE, parsed.get("droppedCauses"));
            assertEquals(keys.indexOf("truncated") + 1, keys.indexOf("droppedCauses"), json);
        }
    }

    @Test
    void recordWithoutCausesHasNoDroppedCausesMarker() {
        var rec = run(true, new PaymentError("a", null), new PaymentError("b", null));
        rec.input = "x".repeat(10_000);
        var out = Truncation.truncateRecord(rec, 2_000, DurableExecutionTelemetryRecord::toByNameWireMap);
        assertEquals(Boolean.TRUE, out.droppedInput);
        assertNull(out.droppedCauses);
    }
}
