// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.CyclicWrapperForTest;
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

        // Anonymous and local classes: Table A applied to Class.getName(), live and from the checkpoint, with no extra
        // digit stripping (javac's binary names are ...ErrorNameTest$1 and ...ErrorNameTest$1LocalError).
        class LocalError extends RuntimeException {}
        Throwable anonymous = new RuntimeException() {};
        assertEquals("1", DurableExecutionTelemetry.toErrorInfo(anonymous).name());
        assertEquals(
                "1LocalError",
                DurableExecutionTelemetry.toErrorInfo(new LocalError()).name());
        for (Throwable t : List.of(anonymous, new LocalError(), new Payment$Error("x"))) {
            String fromLive = DurableExecutionTelemetry.toErrorInfo(t).name();
            String fromCheckpoint = DurableExecutionTelemetry.toErrorInfo(
                            wrapped(t.getClass().getName(), null))
                    .name();
            assertEquals(fromCheckpoint, fromLive, t.getClass().getName());
        }
        // B18: a top-level class named Payment$Error gives "Error" both ways.
        assertEquals(
                "Error",
                DurableExecutionTelemetry.toErrorInfo(new Payment$Error("x")).name());

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

    /** B15: a user exception that subclasses an SDK exception is not an SDK wrapper and reports its own name. */
    public static class UserOperationError extends DurableOperationException {
        public UserOperationError(String message, Throwable cause) {
            super(Operation.builder().id("u").build(), null, message, cause);
        }
    }

    @Test
    void userSubclassOfSdkWrapperReportsItsOwnName() {
        var info = DurableExecutionTelemetry.toErrorInfo(new UserOperationError("mine", new IOException("disk")));
        assertEquals("UserOperationError", info.name());
        assertEquals("mine", info.message());
        assertFalse(DurableExecutionTelemetry.isSdkWrapper(new UserOperationError("mine", null)));
        assertTrue(DurableExecutionTelemetry.isSdkWrapper(wrapped("x", "y")));
        // A callback timeout is an SDK error, never a wrapper.
        assertFalse(DurableExecutionTelemetry.isSdkWrapper(new CallbackTimeoutException(callbackOp(
                OperationStatus.TIMED_OUT,
                ErrorObject.builder().errorType("States.Timeout").build()))));
    }

    @Test
    void userErrorWithCauseIsNotUnwrapped() {
        // B14: a user error is never unwrapped, even if it has a cause.
        assertEquals(
                "PaymentErrorWithCause",
                DurableExecutionTelemetry.toErrorInfo(new PaymentErrorWithCause(new IllegalStateException("inner")))
                        .name());
    }

    static final class PaymentErrorWithCause extends RuntimeException {
        PaymentErrorWithCause(Throwable cause) {
            super("outer", cause);
        }
    }

    @Test
    void nestedSdkWrappersAreFollowedToTheUserError() {
        Throwable chain = new PaymentError("deep");
        for (int i = 0; i < 5; i++) {
            chain = new UnrecoverableDurableExecutionException(
                    ErrorObject.builder()
                            .errorType("w" + i)
                            .errorMessage("w" + i)
                            .build(),
                    false,
                    chain);
        }
        var info = DurableExecutionTelemetry.toErrorInfo(chain);
        assertEquals("PaymentError", info.name());
        assertEquals("w4", info.message(), "message is the outer record's message, unchanged");
    }

    @Test
    void wrapperChainDepthIsBounded() {
        Throwable chain = new PaymentError("deep");
        for (int i = 0; i < 40; i++) {
            chain = new UnrecoverableDurableExecutionException(
                    ErrorObject.builder().errorType("w" + i).errorMessage("w").build(), false, chain);
        }
        // Terminates; the walk stops after 16 levels at an SDK class name.
        assertEquals(
                "UnrecoverableDurableExecutionException",
                DurableExecutionTelemetry.toErrorInfo(chain).name());
    }

    @Test
    void cyclicWrapperChainTerminates() {
        var a = new CyclicWrapperForTest("x.A");
        var b = new CyclicWrapperForTest("x.B");
        a.setOther(b);
        b.setOther(a);
        assertTrue(DurableExecutionTelemetry.isSdkWrapper(a));
        assertEquals(
                "CyclicWrapperForTest", DurableExecutionTelemetry.toErrorInfo(a).name());
    }

    @Test
    void blankCheckpointedTypeUsesTheSdkFailureClassOfTheOperation() {
        // B13: the plugin snapshot hands the exporter a bare DurableOperationException; with a blank errorType the
        // name is the SDK class the core throws for that operation, the same as the execution error.
        for (String blank : new String[] {null, "", "   "}) {
            var error = ErrorObject.builder()
                    .errorType(blank)
                    .errorMessage("declined")
                    .build();
            var op = callbackOp(OperationStatus.FAILED, error);
            assertEquals(
                    "CallbackFailedException",
                    DurableExecutionTelemetry.toErrorInfo(new DurableOperationException(op, error))
                            .name());
            assertEquals(
                    "CallbackFailedException",
                    DurableExecutionTelemetry.toErrorInfo(new CallbackFailedException(op))
                            .name());
        }
        // B11: a timed-out callback is CallbackTimeoutException whatever its checkpoint says.
        var timeoutError = ErrorObject.builder().errorType("States.Timeout").build();
        var timedOut = callbackOp(OperationStatus.TIMED_OUT, timeoutError);
        assertEquals(
                "CallbackTimeoutException",
                DurableExecutionTelemetry.toErrorInfo(new DurableOperationException(timedOut, timeoutError))
                        .name());
        assertEquals(
                "CallbackTimeoutException",
                DurableExecutionTelemetry.toErrorInfo(new CallbackTimeoutException(timedOut))
                        .name());
        // No operation to map from and a base wrapper class: Table A's "Error", never the wrapper's name.
        assertEquals(
                "Error",
                DurableExecutionTelemetry.toErrorInfo(wrapped("  ", "m")).name());
        assertEquals(
                "Error",
                DurableExecutionTelemetry.toErrorInfo(new UnrecoverableDurableExecutionException(
                                ErrorObject.builder().errorMessage("m").build()))
                        .name());
    }

    @Test
    void sdkFailureNamesMatchTheCoreExceptions() {
        assertEquals(
                "StepFailedException",
                DurableExecutionTelemetry.sdkFailureName("STEP", "Step", OperationStatus.FAILED));
        assertEquals(
                "WaitForConditionFailedException",
                DurableExecutionTelemetry.sdkFailureName("STEP", "WaitForCondition", OperationStatus.FAILED));
        assertEquals(
                "CallbackFailedException",
                DurableExecutionTelemetry.sdkFailureName("CALLBACK", "Callback", OperationStatus.FAILED));
        assertEquals(
                "CallbackTimeoutException",
                DurableExecutionTelemetry.sdkFailureName("CALLBACK", "Callback", OperationStatus.TIMED_OUT));
        assertEquals(
                "InvokeFailedException",
                DurableExecutionTelemetry.sdkFailureName("CHAINED_INVOKE", "ChainedInvoke", OperationStatus.FAILED));
        assertEquals(
                "InvokeTimedOutException",
                DurableExecutionTelemetry.sdkFailureName("CHAINED_INVOKE", "ChainedInvoke", OperationStatus.TIMED_OUT));
        assertEquals(
                "InvokeStoppedException",
                DurableExecutionTelemetry.sdkFailureName("CHAINED_INVOKE", "ChainedInvoke", OperationStatus.STOPPED));
        assertEquals(
                "MapIterationFailedException",
                DurableExecutionTelemetry.sdkFailureName("CONTEXT", "MapIteration", OperationStatus.FAILED));
        assertEquals(
                "ParallelBranchFailedException",
                DurableExecutionTelemetry.sdkFailureName("CONTEXT", "ParallelBranch", OperationStatus.FAILED));
        assertEquals(
                "ChildContextFailedException",
                DurableExecutionTelemetry.sdkFailureName("CONTEXT", "RunInChildContext", OperationStatus.FAILED));
        assertNull(DurableExecutionTelemetry.sdkFailureName("CONTEXT", "WaitForCallback", OperationStatus.FAILED));
        assertNull(DurableExecutionTelemetry.sdkFailureName("STEP", "Step", OperationStatus.SUCCEEDED));
        assertNull(DurableExecutionTelemetry.sdkFailureName("WAIT", "Wait", OperationStatus.FAILED));
        assertNull(DurableExecutionTelemetry.sdkFailureName(null, null, null));
    }

    private static Operation callbackOp(OperationStatus status, ErrorObject error) {
        return Operation.builder()
                .id("cb-1")
                .type(OperationType.CALLBACK)
                .status(status)
                .callbackDetails(CallbackDetails.builder()
                        .callbackId("cb-id")
                        .error(error)
                        .build())
                .build();
    }

    private static DurableOperationException wrapped(String errorType, String message) {
        return new DurableOperationException(
                Operation.builder().id("op-1").build(),
                ErrorObject.builder().errorType(errorType).errorMessage(message).build());
    }
}
