// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.DurableExecutionPluginFactory;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Finding {@code arf_v1_qh6xoafzze3z3ccgrbppucmunr} ([P2] remove retained suspended execution state): a warm container
 * must never accumulate per-execution state, including for executions that suspend (PENDING/RETRYING) and never
 * terminate in that container. A resume re-seeds identical stable start time and input.
 *
 * <p>The plugin used to keep that state in an ARN-keyed map and remove the entry at every invocation end, so the test
 * counted the entries left behind. There is no map now — an invocation's state <em>is</em> its plugin instance, which
 * the SDK creates per invocation and drops when it returns — so what is left to prove is about the one object that does
 * outlive invocations: the factory's {@link ExportScheduler}. Two things are asserted, both read directly out of that
 * scheduler under the monitor its fields are guarded by. First, that it owes a finished invocation nothing: no queued
 * record, nothing inside the exporters, no uncompleted drain signal, no drain waiting. Second, that it holds no
 * reference to the instance: {@link ExportScheduler#queue} is the only collection of per-invocation objects it has, so
 * an empty queue after every invocation has ended <em>is</em> "the environment retains nothing", and a retained entry
 * of any kind would fail it — which the old count could not do, because it could only count the entries the plugin knew
 * it had.
 *
 * <p>Reachability from the scheduler is what determines whether state accumulates, and that is a fact about the
 * scheduler's own fields, not about the collector. Whether the JVM has actually reclaimed a finished instance is
 * reported below as a diagnostic and never asserted: {@link System#gc()} is a request the JVM is free to ignore, so an
 * implementation that retains nothing can still leave every weak reference set, and asserting on it would fail the
 * build on garbage-collector behaviour rather than on this plugin's.
 */
class StateCleanupLifecycleTest {

    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");

    private static final class CapturingExporter implements InsightExporter {
        /** Written on pump threads, read on the test thread after a drain; synchronized so the reads are sound. */
        final List<WorkflowInsightRecord> records = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void export(WorkflowInsightRecord record) {
            records.add(record);
        }
    }

    /**
     * An execution environment that emits on every change, so each invocation below really does put records through the
     * scheduler. With the default {@code ON_COMPLETE} mode a suspending invocation emits nothing, and "the environment
     * retains nothing" would hold trivially because nothing was ever queued.
     */
    private static DurableExecutionPluginFactory emittingEnvironment(CapturingExporter exporter) {
        return WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                .addExporter(exporter)
                .build());
    }

    private static String arn(int i) {
        return "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/exec-" + i + "/invocation-1";
    }

    private static Map<String, OperationChangeItemInfo> ops() {
        Map<String, OperationChangeItemInfo> m = new LinkedHashMap<>();
        m.put(
                "op-1",
                new OperationChangeItemInfo(
                        "op-1",
                        "greet",
                        "STEP",
                        "Step",
                        null,
                        START,
                        START.plusMillis(5),
                        OperationStatus.STARTED,
                        1,
                        false,
                        null,
                        null));
        return m;
    }

    private static InvocationInfo start(int i) {
        return new InvocationInfo("req", arn(i), true, START, "in-" + i, ops(), Map.of());
    }

    private static InvocationEndInfo end(int i, InvocationStatus status) {
        return new InvocationEndInfo("req", arn(i), true, START, ops(), status, null, "in-" + i, null);
    }

    /** What a finished invocation leaves behind: the environment that served it, and a way to observe reclamation. */
    private record Finished(ExportScheduler environment, WeakReference<InsightPlugin> instance) {}

    /**
     * Runs one whole invocation in the given environment and returns the environment's scheduler plus a weak reference
     * to the instance that served it, keeping no strong reference of its own — so whatever that reference still points
     * at afterwards is retained by the environment, not by this test.
     *
     * <p>Both assertions are made here, while the instance is still in hand: the scheduler's per-invocation fields for
     * this instance are all clear, and the scheduler's queue does not contain it. Those are the two halves of one
     * documented invariant — an invocation is in the queue exactly while its record is non-null — so checking both
     * catches a state that satisfies one and not the other.
     */
    private static Finished runInvocation(DurableExecutionPluginFactory environment, int i, InvocationStatus status) {
        InsightPlugin plugin = Executions.started(environment, start(i));
        plugin.onInvocationEnd(end(i, status));
        ExportScheduler scheduler = plugin.scheduler;
        assertFalse(Executions.outstanding(plugin), "the scheduler still owes execution " + i + " work");
        assertFalse(scheduler.retains(plugin), "the environment still holds a reference to execution " + i);
        return new Finished(scheduler, new WeakReference<>(plugin));
    }

    /**
     * Diagnostic only, never an assertion: how many finished instances the JVM has reclaimed after being asked to. Kept
     * because it is the observation that first exposed the retained-state finding, and printed so a regression is
     * visible in the build log without a nondeterministic failure.
     */
    private static void reportReclamation(String scenario, List<Finished> finished) {
        System.gc();
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long reclaimed =
                finished.stream().filter(f -> f.instance().get() == null).count();
        System.out.printf(
                "DIAGNOSTIC %s: %d of %d finished plugin instances reclaimed after a System.gc() request%n",
                scenario, reclaimed, finished.size());
    }

    /**
     * Every plugin instance the scheduler still reaches through any of its fields, described as {@code field ->
     * plugin}.
     *
     * <p>The seams above answer the same question for the one collection the scheduler is known to keep. This finds the
     * collection it is <em>not</em> known to keep: a registry reintroduced under any name, keyed by execution ARN or
     * otherwise, shows up here as soon as it holds an instance. That is what makes reachability, rather than
     * collection, the thing this test asserts — and it is deterministic, unlike asking the collector.
     *
     * <p>Read under the scheduler's monitor, which is the monitor its per-invocation fields are guarded by.
     */
    private static List<String> pluginsReachableFrom(ExportScheduler scheduler) {
        var reachable = new ArrayList<String>();
        synchronized (scheduler) {
            for (Field field : ExportScheduler.class.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value;
                try {
                    value = field.get(scheduler);
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("could not read ExportScheduler." + field.getName(), e);
                }
                for (Object element : elementsOf(value)) {
                    if (element instanceof InsightPlugin plugin) {
                        reachable.add(field.getName() + " -> " + plugin);
                    }
                }
            }
        }
        return reachable;
    }

    /** The elements a field value exposes, so a collection or map of any shape can be inspected uniformly. */
    private static Collection<?> elementsOf(Object value) {
        if (value instanceof Collection<?> collection) {
            return new ArrayList<Object>(collection);
        }
        if (value instanceof Map<?, ?> map) {
            var elements = new ArrayList<Object>(map.keySet());
            elements.addAll(map.values());
            return elements;
        }
        return List.of();
    }

    @Test
    void nDistinctPendingExecutionsLeaveNoRetainedState() {
        var exporter = new CapturingExporter();
        var environment = emittingEnvironment(exporter);

        int n = 25;
        var finished = new ArrayList<Finished>();
        for (int i = 0; i < n; i++) {
            // Each execution suspends (PENDING) and never terminates in this container.
            finished.add(runInvocation(environment, i, InvocationStatus.PENDING));
        }

        ExportScheduler scheduler = finished.get(0).environment();
        // Quiesce: returns once nothing is queued and no pump owns the scheduler, so the count below is read at a point
        // where a still-running pump cannot be mistaken for retained state.
        scheduler.drainAll();

        // Every invocation really did put records through the scheduler, so the assertions below are about state that
        // existed and was released, not state that was never created. Counted by distinct execution rather than by
        // record: a RUNNING snapshot that the end record supersedes before any pump takes it is coalesced away by
        // design, so the number of records is not fixed, but every invocation drains its own final record.
        assertEquals(
                n,
                exporter.records.stream()
                        .map(WorkflowInsightRecord::executionArn)
                        .distinct()
                        .count(),
                "every invocation delivered at least one record through the environment's scheduler");
        assertEquals(
                0,
                scheduler.retainedInvocationCount(),
                "the environment still holds per-invocation state after all " + n + " invocations ended");
        assertEquals(
                List.of(),
                pluginsReachableFrom(scheduler),
                "the environment still reaches plugin instances after all " + n + " invocations ended");
        reportReclamation(n + " pending executions", finished);
    }

    @Test
    void retryingSuspendAlsoLeavesNoRetainedState() {
        var exporter = new CapturingExporter();
        var environment = emittingEnvironment(exporter);

        var finished = runInvocation(environment, 0, InvocationStatus.RETRYING);

        finished.environment().drainAll();
        assertFalse(exporter.records.isEmpty(), "the invocation put at least one record through the scheduler");
        assertEquals(
                0,
                finished.environment().retainedInvocationCount(),
                "a RETRYING suspend leaves the environment holding per-invocation state");
        assertEquals(
                List.of(),
                pluginsReachableFrom(finished.environment()),
                "a RETRYING suspend leaves the environment reaching its plugin instance");
        reportReclamation("one retrying execution", List.of(finished));
    }

    @Test
    void resumeReSeedsStableStartTimeAndInput() {
        var exporter = new CapturingExporter();
        var environment = emittingEnvironment(exporter);

        // First invocation with input "alpha", then suspend. Its instance is dropped with it.
        var first = Executions.started(
                environment, new InvocationInfo("req", arn(0), true, START, "alpha", ops(), Map.of()));
        first.onInvocationEnd(new InvocationEndInfo(
                "req", arn(0), true, START, ops(), InvocationStatus.PENDING, null, "alpha", null));

        // Resume invocation: a new instance, seeded from the resume's own hook data (same START, same input).
        var resumed = Executions.started(
                environment, new InvocationInfo("req", arn(0), false, START, "alpha", ops(), Map.of()));
        resumed.onInvocationEnd(new InvocationEndInfo(
                "req", arn(0), true, START, ops(), InvocationStatus.SUCCEEDED, null, "alpha", "out"));

        var terminal = exporter.records.get(exporter.records.size() - 1);
        assertEquals("SUCCEEDED", terminal.status());
        assertEquals(START.toString(), terminal.startTime(), "stable start time recreated across the suspend boundary");
        assertEquals("alpha", terminal.input, "input re-seeded from resume onInvocationStart");
        assertFalse(Executions.outstanding(resumed), "the terminal end leaves the scheduler owing nothing");
        assertFalse(resumed.scheduler.retains(resumed), "the environment holds no reference to the resumed invocation");
        assertEquals(
                0,
                resumed.scheduler.retainedInvocationCount(),
                "neither the suspended invocation nor the resumed one is retained by the environment");
        assertEquals(
                List.of(),
                pluginsReachableFrom(resumed.scheduler),
                "the environment reaches neither the suspended invocation nor the resumed one");
    }
}
