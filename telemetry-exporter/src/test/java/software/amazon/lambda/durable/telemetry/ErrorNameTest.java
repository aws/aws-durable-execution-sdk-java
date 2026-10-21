// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.StepInterruptedException;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/**
 * {@code error.name} is the simple type name of the error the user's code threw — no package, no enclosing-class
 * {@code $} prefix — for the execution {@code error}, {@code operations[].error} and
 * {@code operationsByName.<name>.error}, and is the same whether the failure is live or replayed from a checkpoint.
 */
class ErrorNameTest {

    /** A nested user error, so its binary name is {@code ...ErrorNameTest$PaymentError}. */
    public static class PaymentError extends RuntimeException {
        public PaymentError(String message) {
            super(message);
        }
    }

    private static final class CapturingExporter implements TelemetryExporter {
        final List<DurableExecutionTelemetryRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void export(DurableExecutionTelemetryRecord record) {
            records.add(record);
        }
    }

    private static final StepConfig NO_RETRY =
            StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY).build();

    private static DurableConfig configWith(CapturingExporter exporter, DurableExecutionTelemetryConfig.Builder cfg) {
        return DurableConfig.builder()
                .withPlugins(DurableExecutionTelemetry.durableExecutionTelemetry(
                        cfg.addExporter(exporter).build()))
                .build();
    }

    private static OperationRecord op(DurableExecutionTelemetryRecord rec, String name) {
        return rec.operations().stream()
                .filter(o -> name.equals(o.name()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> byNameError(DurableExecutionTelemetryRecord rec, String name) {
        var byName = (Map<String, Object>) rec.toByNameWireMap().get("operationsByName");
        return (Map<String, Object>) ((Map<String, Object>) byName.get(name)).get("error");
    }

    @Test
    void liveFailureUsesSimpleNameForExecutionOperationAndByName() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "charge",
                        String.class,
                        sc -> {
                            throw new PaymentError("card declined");
                        },
                        NO_RETRY),
                configWith(exporter, DurableExecutionTelemetryConfig.builder()));

        assertEquals(ExecutionStatus.FAILED, runner.run("in").getStatus());
        assertEquals(1, exporter.records.size());
        var rec = exporter.records.get(0);

        assertEquals("PaymentError", rec.error.name(), "execution error");
        assertEquals("card declined", rec.error.message());
        assertEquals("PaymentError", op(rec, "charge").error().name(), "operations[].error");
        assertEquals("card declined", op(rec, "charge").error().message());
        assertEquals("PaymentError", byNameError(rec, "charge").get("name"), "operationsByName.<n>.error");
    }

    @Test
    void replayedFailureInLaterInvocationKeepsTheSameName() {
        var exporter = new CapturingExporter();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    try {
                        context.step(
                                "charge",
                                String.class,
                                sc -> {
                                    throw new PaymentError("card declined");
                                },
                                NO_RETRY);
                    } catch (RuntimeException ignored) {
                        // Swallowed so the failed step is replayed from its checkpoint after the wait.
                    }
                    context.wait("cooldown", Duration.ofSeconds(1));
                    return context.step(
                            "charge-again",
                            String.class,
                            sc -> {
                                throw new PaymentError("still declined");
                            },
                            NO_RETRY);
                },
                configWith(
                        exporter,
                        DurableExecutionTelemetryConfig.builder()
                                .emitMode(DurableExecutionTelemetryConfig.EmitMode.ON_CHANGE)));

        assertEquals(ExecutionStatus.FAILED, runner.runUntilComplete("in").getStatus());
        var records = List.copyOf(exporter.records);
        var last = records.get(records.size() - 1);
        assertEquals("FAILED", last.status());
        assertEquals("PaymentError", last.error.name());
        assertEquals("still declined", last.error.message());
        assertEquals("PaymentError", op(last, "charge-again").error().name());

        // "charge" failed in the first invocation and is replayed from its checkpoint in the second; every record that
        // carries it must report the same simple name.
        long withCharge = 0;
        for (var rec : records) {
            var charge = rec.operations().stream()
                    .filter(o -> "charge".equals(o.name()) && o.error() != null)
                    .findFirst();
            if (charge.isPresent()) {
                withCharge++;
                assertEquals("PaymentError", charge.get().error().name());
                assertEquals("card declined", charge.get().error().message());
            }
        }
        assertTrue(withCharge >= 2, "charge error seen live and after replay, got " + withCharge);
        assertEquals("PaymentError", byNameError(last, "charge").get("name"));
    }

    @Test
    void checkpointedErrorTypeAndLiveThrowableGiveTheSameName() {
        // Live: the user's nested class. Replay: the checkpoint's errorType, which core writes as Class.getName().
        Throwable live = new PaymentError("x");
        assertEquals(
                "software.amazon.lambda.durable.telemetry.ErrorNameTest$PaymentError", PaymentError.class.getName());
        assertEquals("PaymentError", DurableExecutionTelemetry.toErrorInfo(live).name());
        assertEquals(
                "PaymentError",
                DurableExecutionTelemetry.toErrorInfo(wrapped(PaymentError.class.getName(), "x"))
                        .name());

        // Anonymous and local classes: getSimpleName is empty / differs from the binary name, so both paths use the
        // last segment of getName.
        class LocalError extends RuntimeException {}
        Throwable anonymous = new RuntimeException() {};
        for (Throwable t : List.of(anonymous, new LocalError())) {
            String fromLive = DurableExecutionTelemetry.toErrorInfo(t).name();
            String fromCheckpoint = DurableExecutionTelemetry.toErrorInfo(
                            wrapped(t.getClass().getName(), null))
                    .name();
            assertEquals(fromCheckpoint, fromLive, t.getClass().getName());
            assertTrue(!fromLive.isEmpty() && !fromLive.contains("$") && !fromLive.contains("."), fromLive);
        }

        // Plain type strings from other SDKs or service error codes.
        assertEquals("PaymentError", DurableExecutionTelemetry.simpleTypeName("PaymentError"));
        assertEquals("PaymentError", DurableExecutionTelemetry.simpleTypeName("a.b.Outer$Inner$PaymentError"));
        assertEquals("PaymentError", DurableExecutionTelemetry.simpleTypeName("__main__.PaymentError"));
    }

    @Test
    void sdkWrapperWithLiveUserCauseUsesTheCause() {
        var wrapper = new UnrecoverableDurableExecutionException(
                ErrorObject.builder()
                        .errorType("ignored.Type")
                        .errorMessage("wrapped")
                        .build(),
                true,
                new PaymentError("card declined"));
        var info = DurableExecutionTelemetry.toErrorInfo(wrapper);
        assertEquals("PaymentError", info.name());
        assertEquals("wrapped", info.message(), "message unchanged");
    }

    @Test
    void sdkOnlyFailureUsesTheSdkExceptionSimpleName() {
        // Live SDK exception with no user error behind it.
        assertEquals(
                "IllegalDurableOperationException",
                DurableExecutionTelemetry.toErrorInfo(new IllegalDurableOperationException("bad"))
                        .name());
        // Checkpointed SDK error type.
        var interrupted = DurableExecutionTelemetry.toErrorInfo(wrapped(StepInterruptedException.class.getName(), "i"));
        assertEquals("StepInterruptedException", interrupted.name());
        assertNotNull(interrupted.message());
    }

    private static DurableOperationException wrapped(String errorType, String message) {
        return new DurableOperationException(
                Operation.builder().id("op-1").build(),
                ErrorObject.builder().errorType(errorType).errorMessage(message).build());
    }
}
