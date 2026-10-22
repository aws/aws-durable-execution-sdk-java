// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.CallbackDetails;
import software.amazon.awssdk.services.lambda.model.ChainedInvokeDetails;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.exception.NonDeterministicExecutionException;
import software.amazon.lambda.durable.exception.SerDesException;
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

    /**
     * Rule 3 depth, shared with the JS and Python tests: a chain of {@code n} SDK wrappers around {@code PaymentError}.
     * Wrapper {@code k} (1 is the outermost) carries the checkpointed {@code errorType} {@code test.W<k>}. The depth
     * counts unwrap steps: 16 wrappers need 16 unwraps and reach {@code PaymentError}; with 17 or more, the error
     * reached after 16 unwraps is wrapper 17, which is still a wrapper, so its name {@code W17} is reported.
     */
    @Test
    void wrapperChainDepthCountsUnwrapSteps() {
        assertEquals(
                "PaymentError",
                DurableExecutionTelemetry.toErrorInfo(wrapperChain(1)).name());
        assertEquals(
                "PaymentError",
                DurableExecutionTelemetry.toErrorInfo(wrapperChain(15)).name());
        assertEquals(
                "PaymentError",
                DurableExecutionTelemetry.toErrorInfo(wrapperChain(16)).name());
        assertEquals(
                "W17", DurableExecutionTelemetry.toErrorInfo(wrapperChain(17)).name());
        assertEquals(
                "W17", DurableExecutionTelemetry.toErrorInfo(wrapperChain(18)).name());
        assertEquals(
                "W17", DurableExecutionTelemetry.toErrorInfo(wrapperChain(40)).name());
        // The message is the outermost wrapper's checkpointed message, unchanged.
        assertEquals(
                "m1", DurableExecutionTelemetry.toErrorInfo(wrapperChain(17)).message());
    }

    /** {@code n} wrappers, wrapper 1 outermost, wrapper {@code k} with type {@code test.W<k>}, around PaymentError. */
    private static Throwable wrapperChain(int n) {
        Throwable chain = new PaymentError("card declined");
        for (int k = n; k >= 1; k--) {
            chain = new UnrecoverableDurableExecutionException(
                    ErrorObject.builder()
                            .errorType("test.W" + k)
                            .errorMessage("m" + k)
                            .build(),
                    false,
                    chain);
        }
        return chain;
    }

    /**
     * Rule 3 cycles, shared with the JS and Python tests: the walk stops at the first repeat and reports the last error
     * visited before it. The JDK never returns a throwable as its own cause, and the SDK wrappers' causes are fixed at
     * construction, so the cycle is given through the walk's cause lookup.
     */
    @Test
    void cyclicWrapperChainReportsTheLastErrorBeforeTheRepeat() {
        var a = wrapped("test.A", "a");
        var b = wrapped("test.B", "b");
        var self = wrapped("test.Self", "s");
        // Two-cycle A -> B -> A: visited A, B; A repeats; B is reported.
        Map<Throwable, Throwable> twoCycle = new IdentityHashMap<>();
        twoCycle.put(a, b);
        twoCycle.put(b, a);
        assertEquals("B", DurableExecutionTelemetry.errorName(a, twoCycle::get));
        assertEquals("A", DurableExecutionTelemetry.errorName(b, twoCycle::get));
        // Self-cycle S -> S: visited S; S repeats; S is reported.
        Map<Throwable, Throwable> selfCycle = new IdentityHashMap<>();
        selfCycle.put(self, self);
        assertEquals("Self", DurableExecutionTelemetry.errorName(self, selfCycle::get));
        // A cycle that is entered after a prefix: P -> A -> B -> A reports B.
        var p = wrapped("test.P", "p");
        Map<Throwable, Throwable> prefixed = new IdentityHashMap<>(twoCycle);
        prefixed.put(p, a);
        assertEquals("B", DurableExecutionTelemetry.errorName(p, prefixed::get));
    }

    /**
     * R1: wrapper detection is an explicit list matched on the exact runtime class. Every class in the SDK exception
     * package, read from the SDK jar (or its classes directory), must be classified as a wrapper or an SDK error, so a
     * new SDK exception cannot become a wrapper without a decision.
     */
    @Test
    void everySdkExceptionClassIsClassified() throws Exception {
        var pkg = DurableOperationException.class.getPackageName();
        var found = sdkExceptionClassNames(pkg);
        assertTrue(found.size() >= 21, "SDK exception classes found: " + found);
        List<String> unclassified = new ArrayList<>();
        for (String name : found) {
            Class<?> c = Class.forName(name, false, DurableOperationException.class.getClassLoader());
            if (!DurableExecutionTelemetry.SDK_WRAPPERS.contains(c)
                    && !DurableExecutionTelemetry.SDK_ERRORS.contains(c)) {
                unclassified.add(name);
            }
        }
        assertEquals(List.of(), unclassified, "classify each new SDK exception as a wrapper or an SDK error");
        for (var c : DurableExecutionTelemetry.SDK_WRAPPERS) {
            assertFalse(DurableExecutionTelemetry.SDK_ERRORS.contains(c), c + " is in both lists");
            assertEquals(pkg, c.getPackageName());
        }
        for (var c : DurableExecutionTelemetry.SDK_ERRORS) {
            assertEquals(pkg, c.getPackageName());
        }
        // The decisions of the review: these name a failure themselves.
        for (var c : List.of(
                StepInterruptedException.class,
                IllegalDurableOperationException.class,
                NonDeterministicExecutionException.class,
                InvokeTimedOutException.class,
                InvokeStoppedException.class,
                CallbackTimeoutException.class,
                SerDesException.class)) {
            assertTrue(DurableExecutionTelemetry.SDK_ERRORS.contains(c), c.getSimpleName());
        }
    }

    private static List<String> sdkExceptionClassNames(String pkg) throws Exception {
        var dir = pkg.replace('.', '/') + "/";
        var location = Path.of(DurableOperationException.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        List<String> names = new ArrayList<>();
        if (Files.isDirectory(location)) {
            try (var files = Files.list(location.resolve(dir))) {
                files.map(f -> f.getFileName().toString())
                        .filter(f -> f.endsWith(".class"))
                        .forEach(f -> names.add(pkg + "." + f.substring(0, f.length() - ".class".length())));
            }
        } else {
            try (var jar = new JarFile(location.toFile())) {
                jar.stream()
                        .map(JarEntry::getName)
                        .filter(e -> e.startsWith(dir) && e.endsWith(".class"))
                        .filter(e -> e.indexOf('/', dir.length()) < 0)
                        .forEach(e -> names.add(
                                e.substring(0, e.length() - ".class".length()).replace('/', '.')));
            }
        }
        names.removeIf(n -> n.endsWith(".package-info"));
        Collections.sort(names);
        return names;
    }

    @Test
    void sdkErrorsThatAreNotWrappersReportTheirOwnName() {
        // Invoke timeout and stop are SDK errors, like a callback timeout: named after the core's exception, whatever
        // error type the checkpoint carries, live (the core's exception) and from the snapshot (a bare wrapper).
        for (var status : List.of(OperationStatus.TIMED_OUT, OperationStatus.STOPPED)) {
            String expected =
                    status == OperationStatus.TIMED_OUT ? "InvokeTimedOutException" : "InvokeStoppedException";
            for (String type : new String[] {"States.Stopped", "com.acme.PaymentError", null}) {
                var error =
                        ErrorObject.builder().errorType(type).errorMessage("m").build();
                var op = Operation.builder()
                        .id("inv")
                        .type(OperationType.CHAINED_INVOKE)
                        .status(status)
                        .chainedInvokeDetails(
                                ChainedInvokeDetails.builder().error(error).build())
                        .build();
                assertEquals(
                        expected,
                        DurableExecutionTelemetry.toErrorInfo(new DurableOperationException(op, error))
                                .name());
                Throwable live = status == OperationStatus.TIMED_OUT
                        ? new InvokeTimedOutException(op)
                        : new InvokeStoppedException(op);
                assertFalse(DurableExecutionTelemetry.isSdkWrapper(live));
                assertEquals(
                        expected, DurableExecutionTelemetry.toErrorInfo(live).name());
            }
        }
        assertFalse(DurableExecutionTelemetry.isSdkWrapper(new IllegalDurableOperationException("bad")));
        assertFalse(DurableExecutionTelemetry.isSdkWrapper(new NonDeterministicExecutionException("nd")));
        assertEquals(
                "NonDeterministicExecutionException",
                DurableExecutionTelemetry.toErrorInfo(new NonDeterministicExecutionException("nd"))
                        .name());
    }

    /**
     * R2 (Java only): a hidden class's name ends in {@code /0x…}, which changes on every JVM start. The name is cut at
     * the first {@code '/'} before the reduction, live and from the checkpoint.
     */
    @Test
    void hiddenClassNameDropsTheAddressSuffix() throws Throwable {
        byte[] bytes;
        try (InputStream in = HiddenProbeError.class.getResourceAsStream("HiddenProbeError.class")) {
            assertNotNull(in);
            bytes = in.readAllBytes();
        }
        Class<?> hidden = MethodHandles.lookup().defineHiddenClass(bytes, true).lookupClass();
        assertTrue(hidden.isHidden());
        assertTrue(hidden.getName().contains("/"), hidden.getName());
        var error = (Throwable) hidden.getDeclaredConstructor().newInstance();
        assertEquals(
                "HiddenProbeError", DurableExecutionTelemetry.toErrorInfo(error).name());
        assertEquals(
                "HiddenProbeError",
                DurableExecutionTelemetry.toErrorInfo(wrapped(hidden.getName(), "m"))
                        .name());
        assertEquals("Lambda", DurableExecutionTelemetry.javaTypeName("pkg.Foo$$Lambda/0x1"));
        assertEquals("Error", DurableExecutionTelemetry.javaTypeName("/0x1"));
        assertEquals("Lambda", DurableExecutionTelemetry.javaTypeName("pkg.Foo$$Lambda/0x00007f3aBcD"));
        // Only a trailing /0x<hex> is a hidden-class suffix; any other '/' is part of the name.
        assertEquals("PaymentError", DurableExecutionTelemetry.javaTypeName("payments/v1.PaymentError"));
        assertEquals("Foo/0xZZ", DurableExecutionTelemetry.javaTypeName("pkg.Foo/0xZZ"));
        assertEquals("b/0x1", DurableExecutionTelemetry.javaTypeName("a.b/0x1/0x2"));
    }

    /** Rule 6 blank: only the six ASCII whitespace characters; the Table A vectors cover each single character. */
    @Test
    void onlyAsciiWhitespaceIsBlank() {
        assertTrue(DurableExecutionTelemetry.isBlankType(null));
        assertTrue(DurableExecutionTelemetry.isBlankType(""));
        assertTrue(DurableExecutionTelemetry.isBlankType(" \t\n\r\f\u000B"));
        for (char c :
                new char[] {'\u00A0', '\uFEFF', '\u001C', '\u001D', '\u001E', '\u001F', '\u2003', '\u3000', '\u0085'}) {
            assertFalse(DurableExecutionTelemetry.isBlankType(" " + c + " "), Integer.toHexString(c));
            assertEquals(" " + c + " ", DurableExecutionTelemetry.simpleTypeName(" " + c + " "));
        }
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
