// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.SerDesException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * Table B of the cross-SDK {@code error.name} rule, run through {@link LocalDurableTestRunner}. Every scenario runs
 * twice: live, and replayed (the handler catches the error, waits so that a new invocation starts, and rethrows the
 * error, which the SDK then rebuilds from the checkpoint). Each run asserts that {@code error.message} is the original
 * message, that the live and replayed names are equal, and that {@code operationsByName} has the same error as
 * {@code operations[]}.
 */
class ErrorNameScenarioTest {

    static final String MSG = "card declined";

    /** User error type of Table B. */
    public static class PaymentError extends RuntimeException {
        public PaymentError(String message) {
            super(message);
        }

        public PaymentError(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Stand-in for the JS {@code TypeError} of rows B2 and B14. */
    public static class TypeError extends RuntimeException {
        public TypeError(String message) {
            super(message);
        }
    }

    /** B15: a user error that subclasses an SDK exception and has a cause. */
    public static class UserOperationError extends DurableOperationException {
        public UserOperationError(String message, Throwable cause) {
            super(Operation.builder().id("user").build(), null, message, cause);
        }
    }

    /** B17: a step result Jackson cannot serialize, so the SDK fails with its own serialization error. */
    public static class Unserializable {
        public String getValue() {
            throw new IllegalStateException("not serializable");
        }
    }

    enum Mode {
        LIVE,
        REPLAYED
    }

    private static final StepConfig NO_RETRY =
            StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY).build();

    private static final class CapturingExporter implements TelemetryExporter {
        final List<DurableExecutionTelemetryRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void export(DurableExecutionTelemetryRecord record) {
            records.add(record);
        }
    }

    /** Runs {@code failing} directly (live) or catches its error, waits for a new invocation and rethrows (replay). */
    private static <T> T failure(Mode mode, DurableContext ctx, Function<DurableContext, T> failing) {
        if (mode == Mode.LIVE) {
            return failing.apply(ctx);
        }
        RuntimeException caught = null;
        try {
            failing.apply(ctx);
        } catch (RuntimeException e) {
            caught = e;
        }
        if (caught == null) {
            throw new IllegalStateException("scenario did not fail");
        }
        ctx.wait("pause", Duration.ofSeconds(1));
        throw caught;
    }

    private static List<DurableExecutionTelemetryRecord> run(
            BiFunction<String, DurableContext, String> handler,
            Consumer<LocalDurableTestRunner<String, String>> drive) {
        var exporter = new CapturingExporter();
        var config = DurableConfig.builder()
                .withPlugins(
                        DurableExecutionTelemetry.durableExecutionTelemetry(DurableExecutionTelemetryConfig.builder()
                                .operationDetail(DurableExecutionTelemetryConfig.OperationDetail.FULL_TREE)
                                // ON_CHANGE: the invocation that ran a nested operation emits a record carrying it.
                                .emitMode(DurableExecutionTelemetryConfig.EmitMode.ON_CHANGE)
                                .addExporter(exporter)
                                .build()))
                .build();
        var runner = LocalDurableTestRunner.create(String.class, handler, config);
        if (drive == null) {
            assertEquals(ExecutionStatus.FAILED, runner.runUntilComplete("in").getStatus());
        } else {
            drive.accept(runner);
        }
        var records = List.copyOf(exporter.records);
        assertTrue(!records.isEmpty(), "at least one record");
        assertEquals("FAILED", records.get(records.size() - 1).status(), "final record status");
        return records;
    }

    /** What a scenario reports: the execution error and the error of each named operation. */
    private record Observed(ErrorInfo execution, Map<String, ErrorInfo> operations) {}

    /**
     * The execution error comes from the final record. Each operation's error comes from the latest record that carries
     * the operation: a completed child context can replay without its nested operations, so in a replayed run a nested
     * operation is only in the record of the invocation that ran it.
     */
    @SuppressWarnings("unchecked")
    private static Observed observe(List<DurableExecutionTelemetryRecord> records, List<String> opNames) {
        var last = records.get(records.size() - 1);
        Map<String, ErrorInfo> ops = new LinkedHashMap<>();
        for (String name : opNames) {
            DurableExecutionTelemetryRecord rec = null;
            OperationRecord op = null;
            for (int i = records.size() - 1; i >= 0 && op == null; i--) {
                rec = records.get(i);
                op = rec.operations().stream()
                        .filter(o -> name.equals(o.name()) && o.error() != null)
                        .reduce((a, b) -> b)
                        .orElse(null);
            }
            assertNotNull(op, "operations[" + name + "].error in some record; records: " + describe(records));
            var byName = (Map<String, Object>) rec.toByNameWireMap().get("operationsByName");
            var entry = (Map<String, Object>) byName.get(name);
            assertNotNull(entry, "operationsByName." + name);
            var byNameError = (Map<String, Object>) entry.get("error");
            assertNotNull(byNameError, "operationsByName." + name + ".error");
            assertEquals(op.error().name(), byNameError.get("name"), "operationsByName == operations[] for " + name);
            assertEquals(op.error().message(), byNameError.get("message"), "byName message for " + name);
            ops.put(name, op.error());
        }
        return new Observed(last.error, ops);
    }

    private static String describe(List<DurableExecutionTelemetryRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (var r : records) {
            sb.append("\n  ").append(r.status()).append(" error=").append(r.error == null ? null : r.error.name());
            for (var o : r.operations()) {
                sb.append(" | ")
                        .append(o.name())
                        .append('/')
                        .append(o.type())
                        .append('/')
                        .append(o.status());
                sb.append('/').append(o.error() == null ? "-" : o.error().name());
            }
        }
        return sb.toString();
    }

    /**
     * Runs a row live and replayed and checks it. {@code expectedOps} maps operation name to the expected name;
     * {@code expectedOpMessage} is the expected message of every listed operation. A {@code null} expected message is
     * checked by a dedicated test instead (SDK-generated messages).
     */
    private static void check(
            String row,
            Function<Mode, BiFunction<String, DurableContext, String>> handler,
            Consumer<LocalDurableTestRunner<String, String>> drive,
            String expectedExecName,
            String expectedExecMessage,
            Map<String, String> expectedOps,
            String expectedOpMessage) {
        Map<Mode, Observed> seen = new LinkedHashMap<>();
        for (Mode mode : Mode.values()) {
            var obs = observe(run(handler.apply(mode), drive), List.copyOf(expectedOps.keySet()));
            String where = row + " " + mode;
            assertNotNull(obs.execution(), where + " execution error");
            assertEquals(expectedExecName, obs.execution().name(), where + " execution error.name");
            if (expectedExecMessage != null) {
                assertEquals(expectedExecMessage, obs.execution().message(), where + " execution error.message");
            }
            expectedOps.forEach((name, expected) -> {
                assertEquals(expected, obs.operations().get(name).name(), where + " " + name + " error.name");
                if (expectedOpMessage != null) {
                    assertEquals(
                            expectedOpMessage,
                            obs.operations().get(name).message(),
                            where + " " + name + " error.message");
                }
            });
            seen.put(mode, obs);
        }
        var live = seen.get(Mode.LIVE);
        var replayed = seen.get(Mode.REPLAYED);
        assertEquals(live.execution().name(), replayed.execution().name(), row + " live == replayed (execution)");
        expectedOps
                .keySet()
                .forEach(name -> assertEquals(
                        live.operations().get(name).name(),
                        replayed.operations().get(name).name(),
                        row + " live == replayed (" + name + ")"));
    }

    enum Row {
        B1,
        B2,
        B3,
        B4,
        B5,
        B6,
        B7,
        B8,
        B9,
        B10,
        B11,
        B12,
        B13,
        B14,
        B15,
        B16,
        B17,
        B18
    }

    @ParameterizedTest
    @EnumSource(Row.class)
    void tableB(Row row) {
        switch (row) {
            case B1 -> // A step throws PaymentError.
                check(
                        "B1",
                        m -> (in, ctx) -> failure(
                                m,
                                ctx,
                                c -> c.step(
                                        "charge",
                                        String.class,
                                        sc -> {
                                            throw new PaymentError(MSG);
                                        },
                                        NO_RETRY)),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of("charge", "PaymentError"),
                        MSG);
            case B2 -> { // A step fails once with TypeError, then on retry with PaymentError.
                var retryOnce = StepConfig.builder()
                        .retryStrategy(RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)))
                        .build();
                check(
                        "B2",
                        m -> (in, ctx) -> failure(
                                m,
                                ctx,
                                c -> c.step(
                                        "charge",
                                        String.class,
                                        sc -> {
                                            if (sc.getAttempt() == 1) {
                                                throw new TypeError("first attempt");
                                            }
                                            throw new PaymentError(MSG);
                                        },
                                        retryOnce)),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of("charge", "PaymentError"),
                        MSG);
            }
            case B3 -> // A child context throws PaymentError.
                check(
                        "B3",
                        m -> (in, ctx) -> failure(
                                m,
                                ctx,
                                c -> c.runInChildContext("child", String.class, cc -> {
                                    throw new PaymentError(MSG);
                                })),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of("child", "PaymentError"),
                        MSG);
            case B4 -> // A child context contains a step that throws PaymentError.
                check(
                        "B4",
                        m -> (in, ctx) -> failure(
                                m,
                                ctx,
                                c -> c.runInChildContext(
                                        "child",
                                        String.class,
                                        cc -> cc.step(
                                                "charge",
                                                String.class,
                                                sc -> {
                                                    throw new PaymentError(MSG);
                                                },
                                                NO_RETRY))),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of("charge", "PaymentError", "child", "PaymentError"),
                        MSG);
            case B5 -> // A map item throws PaymentError.
                check(
                        "B5",
                        m -> (in, ctx) -> failure(m, ctx, c -> mapAndRethrow(c, false)),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of("fanout-iteration-0", "PaymentError"),
                        MSG);
            case B6 -> // A map item's step throws PaymentError.
                check(
                        "B6",
                        m -> (in, ctx) -> failure(m, ctx, c -> mapAndRethrow(c, true)),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of("charge", "PaymentError", "fanout-iteration-0", "PaymentError"),
                        MSG);
            case B7 -> // A parallel branch throws PaymentError.
                check(
                        "B7",
                        m -> (in, ctx) -> failure(m, ctx, c -> {
                            try (var p = c.parallel("par")) {
                                var bad = p.branch("bad", String.class, bc -> {
                                    throw new PaymentError(MSG);
                                });
                                p.get();
                                return bad.get();
                            }
                        }),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of("bad", "PaymentError"),
                        MSG);
            case B8 -> // A waitForCondition check throws PaymentError.
                check(
                        "B8",
                        m -> (in, ctx) -> failure(
                                m,
                                ctx,
                                c -> c.waitForCondition("poll", String.class, (String state, StepContext sc) -> {
                                    throw new PaymentError(MSG);
                                })),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of("poll", "PaymentError"),
                        MSG);
            case B9 -> // An invoked function throws PaymentError.
                check(
                        "B9",
                        m -> (in, ctx) -> failure(m, ctx, c -> c.invoke("callee", "fn", "x", String.class)),
                        r -> {
                            r.run("in");
                            r.failChainedInvoke(
                                    "callee",
                                    ErrorObject.builder()
                                            .errorType(PaymentError.class.getName())
                                            .errorMessage(MSG)
                                            .build());
                            assertEquals(
                                    ExecutionStatus.FAILED,
                                    r.runUntilComplete("in").getStatus());
                        },
                        "PaymentError",
                        MSG,
                        Map.of("callee", "PaymentError"),
                        MSG);
            case B10 -> // The waitForCallback submitter throws PaymentError.
                check(
                        "B10",
                        m -> (in, ctx) -> failure(
                                m,
                                ctx,
                                c -> c.waitForCallback("wfc", String.class, (id, sc) -> {
                                    throw new PaymentError(MSG);
                                })),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of("wfc-submitter", "PaymentError", "wfc", "PaymentError"),
                        MSG);
            case B11 -> // A callback times out. The checkpoint carries no error, so the operation has no message.
                check(
                        "B11",
                        m -> (in, ctx) -> failure(
                                m,
                                ctx,
                                c -> c.createCallback("cb", String.class).get()),
                        r -> {
                            r.run("in");
                            r.timeoutCallback(r.getCallbackId("cb"));
                            assertEquals(
                                    ExecutionStatus.FAILED,
                                    r.runUntilComplete("in").getStatus());
                        },
                        CallbackTimeoutException.class.getSimpleName(),
                        null, // see callbackTimeoutMessages
                        Map.of("cb", CallbackTimeoutException.class.getSimpleName()),
                        null);
            case B12 -> // A callback is failed externally with type PaymentError.
                check(
                        "B12",
                        m -> (in, ctx) -> failure(
                                m,
                                ctx,
                                c -> c.createCallback("cb", String.class).get()),
                        failCallback("com.acme.PaymentError"),
                        "PaymentError",
                        MSG,
                        Map.of("cb", "PaymentError"),
                        MSG);
            case B13 -> { // A callback is failed externally with no type.
                for (String blank : new String[] {null, "", "  "}) {
                    check(
                            "B13[" + blank + "]",
                            m -> (in, ctx) -> failure(
                                    m,
                                    ctx,
                                    c -> c.createCallback("cb", String.class).get()),
                            failCallback(blank),
                            CallbackFailedException.class.getSimpleName(),
                            MSG,
                            Map.of("cb", CallbackFailedException.class.getSimpleName()),
                            MSG);
                }
            }
            case B14 -> // The handler throws PaymentError, which has its own cause TypeError.
                check(
                        "B14",
                        m -> (in, ctx) -> failure(m, ctx, c -> {
                            throw new PaymentError(MSG, new TypeError("cause"));
                        }),
                        null,
                        "PaymentError",
                        MSG,
                        Map.of(),
                        null);
            case B15 -> // The handler throws a user subclass of an SDK exception that has a cause.
                check(
                        "B15",
                        m -> (in, ctx) -> failure(m, ctx, c -> {
                            throw new UserOperationError(MSG, new IOException("disk"));
                        }),
                        null,
                        "UserOperationError",
                        MSG,
                        Map.of(),
                        null);
            case B16 -> // Java analogue: a handled step error, then a child context fails with an SDK error.
                check(
                        "B16",
                        m -> (in, ctx) -> failure(m, ctx, c -> {
                            try {
                                c.step(
                                        "validate",
                                        String.class,
                                        sc -> {
                                            throw new IllegalArgumentException("x");
                                        },
                                        NO_RETRY);
                            } catch (IllegalArgumentException handled) {
                                // handled
                            }
                            return c.runInChildContext("child", String.class, cc -> {
                                throw new SerDesException("x");
                            });
                        }),
                        null,
                        "SerDesException",
                        "x",
                        Map.of("child", "SerDesException"),
                        "x");
            case B17 -> // An SDK-only failure with no user error: the step result cannot be serialized.
                check(
                        "B17",
                        m -> (in, ctx) -> failure(m, ctx, c -> {
                            c.step("encode", Unserializable.class, sc -> new Unserializable(), NO_RETRY);
                            return "unreachable";
                        }),
                        null,
                        "SerDesException",
                        null, // SDK-generated serialization message
                        Map.of("encode", "SerDesException"),
                        null);
            case B18 -> // A class named Payment$Error is thrown from a step.
                check(
                        "B18",
                        m -> (in, ctx) -> failure(
                                m,
                                ctx,
                                c -> c.step(
                                        "charge",
                                        String.class,
                                        sc -> {
                                            throw new Payment$Error(MSG);
                                        },
                                        NO_RETRY)),
                        null,
                        "Error",
                        MSG,
                        Map.of("charge", "Error"),
                        MSG);
            default -> fail("unhandled row " + row);
        }
    }

    /**
     * Java {@code map} returns item failures as data ({@code MapResult.MapError}) rather than throwing, so the handler
     * rethrows the failed item's user error type with its message; the execution error then propagates from the item.
     */
    private static String mapAndRethrow(DurableContext ctx, boolean viaStep) {
        var result = ctx.map("fanout", List.of("a"), String.class, (item, i, mc) -> {
            if (viaStep) {
                return mc.step(
                        "charge",
                        String.class,
                        sc -> {
                            throw new PaymentError(MSG);
                        },
                        NO_RETRY);
            }
            throw new PaymentError(MSG);
        });
        var error = result.getError(0);
        assertNotNull(error, "map item failed");
        assertEquals(PaymentError.class.getName(), error.errorType());
        throw new PaymentError(error.errorMessage());
    }

    private static Consumer<LocalDurableTestRunner<String, String>> failCallback(String errorType) {
        return r -> {
            r.run("in");
            r.failCallback(
                    r.getCallbackId("cb"),
                    ErrorObject.builder().errorType(errorType).errorMessage(MSG).build());
            assertEquals(ExecutionStatus.FAILED, r.runUntilComplete("in").getStatus());
        };
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void callbackTimeoutMessages(Mode mode) {
        var records = run(
                (in, ctx) -> failure(
                        mode, ctx, c -> c.createCallback("cb", String.class).get()),
                r -> {
                    r.run("in");
                    r.timeoutCallback(r.getCallbackId("cb"));
                    r.runUntilComplete("in");
                });
        var rec = records.get(records.size() - 1);
        assertTrue(rec.error.message().startsWith("Callback timed out: "), rec.error.message());
        var op = rec.operations().stream()
                .filter(o -> "cb".equals(o.name()))
                .findFirst()
                .orElseThrow();
        assertNull(op.error().message(), "no checkpointed error, so no operation message");
    }
}
