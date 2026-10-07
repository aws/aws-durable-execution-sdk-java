// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.lambda.durable.config.StepSemantics.AT_MOST_ONCE_PER_RETRY;
import static software.amazon.lambda.durable.retry.RetryStrategies.Presets.NO_RETRY;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

class CheckpointTokenRevocationIntegrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptedStepOutcomeReplaysAfterResumeWithoutRepeatingUserCode(boolean fail) {
        var calls = new AtomicInteger();
        var runner = createPausedRunner(fail, calls);

        assertEquals(ExecutionStatus.PENDING, runner.runUntilComplete("input").getStatus());
        assertEquals(1, calls.get());
        runner.resumeExecution();
        var resumed = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, resumed.getStatus());
        assertEquals(fail ? "stored-error" : "stored-result", resumed.getResult(String.class));
        assertEquals(1, calls.get());
    }

    private LocalDurableTestRunner<String, String> createPausedRunner(boolean fail, AtomicInteger calls) {
        var runnerRef = new AtomicReference<LocalDurableTestRunner<String, String>>();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            try {
                return ctx.step(
                        "once",
                        String.class,
                        stepCtx -> {
                            calls.incrementAndGet();
                            runnerRef.get().pauseExecution();
                            if (fail) {
                                throw new IllegalArgumentException("stored-error");
                            }
                            return "stored-result";
                        },
                        noRetryConfig());
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        });
        runnerRef.set(runner);
        return runner;
    }

    private StepConfig noRetryConfig() {
        return StepConfig.builder()
                .semanticsPerRetry(AT_MOST_ONCE_PER_RETRY)
                .retryStrategy(NO_RETRY)
                .build();
    }
}
