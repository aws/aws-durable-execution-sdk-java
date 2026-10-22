// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.CallbackTimeoutException;
import software.amazon.lambda.durable.exception.ChildContextFailedException;
import software.amazon.lambda.durable.exception.InvokeStoppedException;
import software.amazon.lambda.durable.exception.InvokeTimedOutException;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.TestResult;

/**
 * A failed context checkpoints the wrapped (original) error, never the SDK wrapper, and the live and replayed failure
 * that user code catches has the same type and error object.
 */
class ChildContextWrappedErrorIntegrationTest {

    static final String MSG = "card declined";

    public static class PaymentError extends RuntimeException {
        public PaymentError(String message) {
            super(message);
        }
    }

    private static final StepConfig NO_RETRY =
            StepConfig.builder().retryStrategy(RetryStrategies.Presets.NO_RETRY).build();

    /** What user code caught: the exception class and the type and message of its error object. */
    private record Caught(String exception, String errorType, String errorMessage) {}

    private static Caught caught(Throwable t) {
        var eo = t instanceof software.amazon.lambda.durable.exception.DurableOperationException d
                ? d.getErrorObject()
                : null;
        return new Caught(
                t.getClass().getName(),
                eo == null ? null : eo.errorType(),
                eo == null ? t.getMessage() : eo.errorMessage());
    }

    private record Outcome(Caught live, Caught replayed, TestResult<String> result) {}

    /**
     * Runs {@code failing}, records what it threw, waits so that a new invocation starts, and records what the replay
     * of {@code failing} throws.
     */
    private static Outcome run(
            Function<DurableContext, Object> failing, Consumer<LocalDurableTestRunner<String, String>> drive) {
        List<Caught> seen = Collections.synchronizedList(new ArrayList<>());
        var runner = LocalDurableTestRunner.create(String.class, (String in, DurableContext ctx) -> {
            try {
                failing.apply(ctx);
                throw new IllegalStateException("did not fail");
            } catch (SuspendExecutionException e) {
                throw e;
            } catch (RuntimeException e) {
                seen.add(caught(e));
            }
            ctx.wait("pause", Duration.ofSeconds(1));
            return "done";
        });
        if (drive != null) {
            runner.run("in");
            drive.accept(runner);
        }
        var result = runner.runUntilComplete("in");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(2, seen.size(), "one live and one replayed failure: " + seen);
        return new Outcome(seen.get(0), seen.get(1), result);
    }

    private static ErrorObject contextError(TestResult<String> result, String name) {
        var details = result.getOperation(name).getContextDetails();
        assertNotNull(details, name + " context details");
        return details.error();
    }

    private static ErrorObject error(String type) {
        return ErrorObject.builder().errorType(type).errorMessage(MSG).build();
    }

    private static Object invokeInChild(DurableContext ctx) {
        return ctx.runInChildContext("child", String.class, cc -> cc.invoke("callee", "fn", "x", String.class));
    }

    private static Object callbackInChild(DurableContext ctx) {
        return ctx.runInChildContext(
                "child",
                String.class,
                cc -> cc.createCallback("cb", String.class).get());
    }

    enum Scenario {
        STEP_ERROR,
        INVOKE_FAILED,
        INVOKE_STOPPED,
        INVOKE_STOPPED_USER_TYPE,
        INVOKE_TIMED_OUT,
        CALLBACK_FAILED,
        CALLBACK_FAILED_NO_TYPE,
        CALLBACK_FAILED_TYPE_ERROR,
        CALLBACK_TIMED_OUT
    }

    @ParameterizedTest
    @EnumSource(Scenario.class)
    void contextCheckpointsTheWrappedErrorAndReplaysTheSameShape(Scenario scenario) {
        Outcome outcome;
        String type;
        String message = MSG;
        switch (scenario) {
            case STEP_ERROR -> {
                outcome = run(
                        c -> c.runInChildContext(
                                "child",
                                String.class,
                                cc -> cc.step(
                                        "charge",
                                        String.class,
                                        sc -> {
                                            throw new PaymentError(MSG);
                                        },
                                        NO_RETRY)),
                        null);
                type = PaymentError.class.getName();
            }
            case INVOKE_FAILED -> {
                outcome = run(
                        ChildContextWrappedErrorIntegrationTest::invokeInChild,
                        r -> r.failChainedInvoke("callee", error("com.acme.PaymentError")));
                type = "com.acme.PaymentError";
            }
            case INVOKE_STOPPED -> {
                outcome = run(
                        ChildContextWrappedErrorIntegrationTest::invokeInChild,
                        r -> r.stopChainedInvoke("callee", error("States.Stopped")));
                type = InvokeStoppedException.class.getName();
            }
            case INVOKE_STOPPED_USER_TYPE -> {
                outcome = run(
                        ChildContextWrappedErrorIntegrationTest::invokeInChild,
                        r -> r.stopChainedInvoke("callee", error("com.acme.PaymentError")));
                type = InvokeStoppedException.class.getName();
            }
            case INVOKE_TIMED_OUT -> {
                outcome = run(
                        ChildContextWrappedErrorIntegrationTest::invokeInChild, r -> r.timeoutChainedInvoke("callee"));
                type = InvokeTimedOutException.class.getName();
                message = null;
            }
            case CALLBACK_FAILED -> {
                outcome = run(
                        ChildContextWrappedErrorIntegrationTest::callbackInChild,
                        r -> r.failCallback(r.getCallbackId("cb"), error("com.acme.PaymentError")));
                type = "com.acme.PaymentError";
            }
            case CALLBACK_FAILED_NO_TYPE -> {
                outcome = run(
                        ChildContextWrappedErrorIntegrationTest::callbackInChild,
                        r -> r.failCallback(r.getCallbackId("cb"), error(null)));
                type = CallbackFailedException.class.getName();
            }
            case CALLBACK_FAILED_TYPE_ERROR -> {
                // The explicit type "Error" is a type: it is kept, unlike a missing one.
                outcome = run(
                        ChildContextWrappedErrorIntegrationTest::callbackInChild,
                        r -> r.failCallback(r.getCallbackId("cb"), error("Error")));
                type = "Error";
            }
            case CALLBACK_TIMED_OUT -> {
                outcome = run(
                        ChildContextWrappedErrorIntegrationTest::callbackInChild,
                        r -> r.timeoutCallback(r.getCallbackId("cb")));
                type = CallbackTimeoutException.class.getName();
                message = null; // SDK message with the callback id, checked below
            }
            default -> throw new IllegalArgumentException(scenario.name());
        }

        var checkpoint = contextError(outcome.result(), "child");
        assertNotNull(checkpoint, "context checkpoint error");
        assertEquals(type, checkpoint.errorType(), "context checkpoint errorType");
        if (scenario == Scenario.CALLBACK_TIMED_OUT) {
            assertEquals(
                    "Callback timed out: "
                            + outcome.result()
                                    .getOperation("cb")
                                    .getCallbackDetails()
                                    .callbackId(),
                    checkpoint.errorMessage());
        } else {
            assertEquals(message, checkpoint.errorMessage(), "context checkpoint errorMessage");
        }

        assertEquals(outcome.live(), outcome.replayed(), "live and replayed failures have the same shape");
        if (scenario == Scenario.STEP_ERROR) {
            assertEquals(PaymentError.class.getName(), outcome.live().exception());
        } else {
            assertEquals(
                    ChildContextFailedException.class.getName(), outcome.live().exception());
            assertEquals(type, outcome.live().errorType());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = Scenario.class,
            names = {"INVOKE_STOPPED", "INVOKE_TIMED_OUT"})
    void nestedContextsKeepTheSdkErrorIdentity(Scenario scenario) {
        var outcome = run(
                c -> c.runInChildContext(
                        "outer", String.class, oc -> invokeInChild(oc).toString()),
                r -> {
                    if (scenario == Scenario.INVOKE_STOPPED) {
                        r.stopChainedInvoke("callee", error("States.Stopped"));
                    } else {
                        r.timeoutChainedInvoke("callee");
                    }
                });
        String type = scenario == Scenario.INVOKE_STOPPED
                ? InvokeStoppedException.class.getName()
                : InvokeTimedOutException.class.getName();
        assertEquals(type, contextError(outcome.result(), "child").errorType(), "inner context");
        assertEquals(type, contextError(outcome.result(), "outer").errorType(), "outer context");
        assertEquals(outcome.live(), outcome.replayed());
        assertEquals(type, outcome.live().errorType());
    }

    @ParameterizedTest
    @EnumSource(
            value = Scenario.class,
            names = {"INVOKE_STOPPED", "CALLBACK_TIMED_OUT"})
    void mapItemCheckpointCarriesTheSdkErrorType(Scenario scenario) {
        var runner = LocalDurableTestRunner.create(String.class, (String in, DurableContext ctx) -> {
            var result = ctx.map("fanout", List.of("a"), String.class, (item, i, mc) -> {
                if (scenario == Scenario.INVOKE_STOPPED) {
                    return mc.invoke("callee", "fn", "x", String.class);
                }
                return mc.createCallback("cb", String.class).get();
            });
            return result.getError(0) == null ? "no error" : "failed";
        });
        runner.run("in");
        if (scenario == Scenario.INVOKE_STOPPED) {
            runner.stopChainedInvoke("callee", error("States.Stopped"));
        } else {
            runner.timeoutCallback(runner.getCallbackId("cb"));
        }
        var result = runner.runUntilComplete("in");
        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        String type = scenario == Scenario.INVOKE_STOPPED
                ? InvokeStoppedException.class.getName()
                : CallbackTimeoutException.class.getName();
        assertEquals("failed", result.getResult(String.class));
        assertEquals(type, contextError(result, "fanout-iteration-0").errorType());
    }

    /**
     * {@code waitForCallback} rethrows the callback's own exception live, so its context keeps the callback's own error
     * object: a replay that can read only the completed context then reports the same error object.
     */
    @ParameterizedTest
    @EnumSource(
            value = Scenario.class,
            names = {"CALLBACK_FAILED", "CALLBACK_FAILED_NO_TYPE", "CALLBACK_TIMED_OUT"})
    void waitForCallbackContextKeepsTheCallbackErrorObject(Scenario scenario) {
        var outcome = run(c -> c.waitForCallback("wfc", String.class, (id, sc) -> {}), r -> {
            switch (scenario) {
                case CALLBACK_TIMED_OUT -> r.timeoutCallback(r.getCallbackId("wfc-callback"));
                case CALLBACK_FAILED -> r.failCallback(r.getCallbackId("wfc-callback"), error("com.acme.PaymentError"));
                default -> r.failCallback(r.getCallbackId("wfc-callback"), error(null));
            }
        });
        var callbackError = outcome.result()
                .getOperation("wfc-callback")
                .getCallbackDetails()
                .error();
        assertEquals(callbackError, contextError(outcome.result(), "wfc"));
        assertEquals(outcome.live().errorType(), outcome.replayed().errorType());
        String live = scenario == Scenario.CALLBACK_TIMED_OUT
                ? CallbackTimeoutException.class.getName()
                : CallbackFailedException.class.getName();
        assertEquals(live, outcome.live().exception());
    }
}
