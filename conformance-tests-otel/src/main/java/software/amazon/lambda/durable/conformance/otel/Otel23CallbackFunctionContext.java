// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.conformance.otel;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import java.time.Duration;
import java.util.Map;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.retry.WaitStrategies;

/** Retry, polling, submitter, and helper callback contexts for OTel requirement 23. */
public final class Otel23CallbackFunctionContext extends OtelConformanceHandler<String> {
    private static final String HELPER_FAILURE = "intentional-helper-failure";

    @Override
    public String handleRequest(Map<String, Object> event, DurableContext context) {
        requireScenario(event, "callback-function-context");
        runRetryStep(context);
        runCondition(context);
        context.waitForCallback(
                "otel-context-callback", String.class, (callbackId, step) -> probe("callback-submitter"));
        runFailedHelper(context);
        context.runInChildContext(
                "otel-context-virtual",
                String.class,
                child -> {
                    probe("virtual-child");
                    return "virtual";
                },
                RunInChildContextConfig.builder().isVirtual(true).build());
        return "callback-context-complete";
    }

    private static void runRetryStep(DurableContext context) {
        context.step(
                "otel-context-retry-step",
                String.class,
                step -> {
                    var attempt = step.getAttempt();
                    probe("retry-attempt-" + attempt);
                    if (attempt == 1) throw new IllegalStateException("intentional-step-retry");
                    return "retried";
                },
                StepConfig.builder()
                        .retryStrategy(RetryStrategies.fixedDelay(2, Duration.ofSeconds(1)))
                        .build());
    }

    private static void runCondition(DurableContext context) {
        context.waitForCondition(
                "otel-context-condition",
                Integer.class,
                (state, step) -> {
                    var next = state + 1;
                    probe("condition-check-" + next);
                    return next == 2
                            ? WaitForConditionResult.stopPolling(next)
                            : WaitForConditionResult.continuePolling(next);
                },
                WaitForConditionConfig.<Integer>builder()
                        .initialState(0)
                        .waitStrategy(WaitStrategies.fixedDelay(2, Duration.ofSeconds(1)))
                        .build());
    }

    private static void runFailedHelper(DurableContext context) {
        try {
            context.withRetry(
                    "otel-context-with-retry",
                    (attempt, child) -> {
                        probe("with-retry-body");
                        throw new IllegalStateException(HELPER_FAILURE);
                    },
                    WithRetryConfig.builder()
                            .wrapInChildContext(true)
                            .retryStrategy((error, attempt) -> {
                                probe("with-retry-strategy");
                                return RetryDecision.fail();
                            })
                            .build());
        } catch (RuntimeException error) {
            var message =
                    error instanceof DurableOperationException operationError && operationError.getErrorObject() != null
                            ? operationError.getErrorObject().errorMessage()
                            : error.getMessage();
            if (!HELPER_FAILURE.equals(message)) throw error;
        }
    }

    private static void probe(String label) {
        if (!Span.current().getSpanContext().isValid()) {
            throw new IllegalStateException("No active span context for " + label);
        }
        var span = GlobalOpenTelemetry.getTracer("durable-conformance")
                .spanBuilder("conformance." + label)
                .setAttribute("conformance.callback", label)
                .startSpan();
        span.end();
    }
}
