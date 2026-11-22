// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
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

    @Test
    void waitFinishedInOlderInvocation_staysReplay() {
        var states = new ArrayList<String>();
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            ctx.wait("w1", Duration.ofSeconds(1));
            states.add("afterW1=" + ctx.isReplaying());
            ctx.wait("w2", Duration.ofSeconds(1));
            states.add("afterW2=" + ctx.isReplaying());
            return "done";
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("x").getStatus());
        runner.advanceTime();
        assertEquals(ExecutionStatus.PENDING, runner.run("x").getStatus());
        states.clear();
        runner.advanceTime();
        assertEquals(ExecutionStatus.SUCCEEDED, runner.run("x").getStatus());
        assertEquals(List.of("afterW1=true", "afterW2=false"), states);
    }

    @Test
    void logsAfterWait_reachTheLogger_andReplayedLogsDoNot() {
        var messages = new ArrayList<String>();
        var delegate = recordingLogger(messages);
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var logger = ctx.getLogger(delegate);
            logger.info("before wait");
            ctx.wait("pause", Duration.ofSeconds(1));
            logger.info("after wait");
            return "done";
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("x").getStatus());
        assertEquals(List.of("before wait"), messages);
        runner.advanceTime();
        assertEquals(ExecutionStatus.SUCCEEDED, runner.run("x").getStatus());
        assertEquals(List.of("before wait", "after wait"), messages);
    }

    @Test
    void childContextWaitCompletedDuringSuspension_childLogsReachTheLogger() {
        var messages = new ArrayList<String>();
        var delegate = recordingLogger(messages);
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> ctx.runInChildContext(
                "child", String.class, child -> {
                    var logger = child.getLogger(delegate);
                    logger.info("child before wait");
                    child.wait("pause", Duration.ofSeconds(1));
                    logger.info("child after wait");
                    return "done";
                }));

        assertEquals(ExecutionStatus.PENDING, runner.run("x").getStatus());
        runner.advanceTime();
        assertEquals(ExecutionStatus.SUCCEEDED, runner.run("x").getStatus());
        assertEquals(List.of("child before wait", "child after wait"), messages);
    }

    @Test
    void parallelBranchWaitCompletedDuringSuspension_branchLogsReachTheLogger() {
        var messages = new ArrayList<String>();
        var delegate = recordingLogger(messages);
        var runner = LocalDurableTestRunner.create(String.class, (input, ctx) -> {
            var futures = new ArrayList<DurableFuture<String>>();
            var parallel = ctx.parallel("par");
            try (parallel) {
                futures.add(parallel.branch("branch-a", String.class, branch -> {
                    var logger = branch.getLogger(delegate);
                    logger.info("branch before wait");
                    branch.wait("pause", Duration.ofSeconds(1));
                    logger.info("branch after wait");
                    return "A";
                }));
            }
            parallel.get();
            return futures.get(0).get();
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("x").getStatus());
        runner.advanceTime();
        assertEquals(ExecutionStatus.SUCCEEDED, runner.run("x").getStatus());
        assertEquals(List.of("branch before wait", "branch after wait"), messages);
    }

    private static Logger recordingLogger(List<String> messages) {
        return (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(), new Class<?>[] {Logger.class}, (proxy, method, args) -> {
                    var name = method.getName();
                    if (name.equals("info") && args != null && args.length > 0) {
                        messages.add((String) args[0]);
                        return null;
                    }
                    if (name.startsWith("is") && name.endsWith("Enabled")) {
                        return true;
                    }
                    if (name.equals("getName")) {
                        return "recording";
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }
}
