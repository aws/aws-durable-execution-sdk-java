// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;

/** The context must leave replay mode once the first operation that completed during suspension is consumed. */
class ReplayModeAfterSuspensionTest {

    @Test
    void waitCompletedDuringSuspension_codeAfterWaitIsNotReplay() {
        var replayStates = new ArrayList<Boolean>();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            ctx.step("before", String.class, s -> "a");
            ctx.wait("pause", Duration.ofSeconds(1));
            replayStates.add(ctx.isReplaying());
            return "done";
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("x").getStatus());
        replayStates.clear();
        runner.advanceTime();
        assertEquals(ExecutionStatus.SUCCEEDED, runner.run("x").getStatus());
        assertEquals(List.of(false), replayStates);
    }

    @Test
    void callbackCompletedDuringSuspension_codeAfterGetIsNotReplay() {
        var replayStates = new ArrayList<Boolean>();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var cb = ctx.createCallback("approval", String.class);
            var value = cb.get();
            replayStates.add(ctx.isReplaying());
            return value;
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("x").getStatus());
        replayStates.clear();
        runner.completeCallback(runner.getCallbackId("approval"), "\"ok\"");
        assertEquals(ExecutionStatus.SUCCEEDED, runner.run("x").getStatus());
        assertEquals(List.of(false), replayStates);
    }

    @Test
    void waitAsyncThenStep_intermediateStepStaysReplay() {
        var states = new ArrayList<String>();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var w = ctx.waitAsync("pause", Duration.ofSeconds(1));
            ctx.step("middle", String.class, s -> "m");
            states.add("middle=" + ctx.isReplaying());
            w.get();
            states.add("after=" + ctx.isReplaying());
            return "done";
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("x").getStatus());
        states.clear();
        runner.advanceTime();
        assertEquals(ExecutionStatus.SUCCEEDED, runner.run("x").getStatus());
        assertEquals(List.of("middle=true", "after=false"), states);
    }
}
