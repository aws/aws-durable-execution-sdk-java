// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.OperationChangeItemInfo;

/**
 * Build order, not hand-off order, decides which record an invocation exports.
 *
 * <p>Customer code runs while a record is being built, on the hook thread, before anything is scheduled: the input and
 * output content transforms, an operation's result transform, and any Jackson serializer registered for a customer
 * type. That code can call back into a hook of the same plugin instance, which builds and hands over a newer record
 * while the outer build is still running. The outer build then hands over an older snapshot last, and the scheduler's
 * per-invocation slot takes the last hand-off with no comparison of record ages.
 *
 * <p>Two outcomes follow if nothing orders the two records. With the pump held, the newer record is coalesced away and
 * only the older snapshot is exported. With the pump running immediately, the exporter sees the newer record and then
 * the older one, so the last state a destination records for the execution is stale.
 *
 * <p>The plugin takes a build revision before each build and the scheduler queues the record only while that revision
 * is still the newest, so an overtaken build's record is dropped. The final record is exempt from that check and is
 * ordered by the invocation's {@code closed} flag instead, so a newer RUNNING build started from inside the final
 * record's own transforms cannot drop it.
 *
 * <p>The SDK serializes change hooks for one execution today, so the re-entrant hook here is forced rather than
 * observed in production. The plugin must not depend on that: nothing in the SDK pins it, and the three language SDKs
 * carry the same guard.
 */
class RecordSupersessionTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:111122223333:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-08-05T00:00:00Z");
    private static final String INPUT = "payload";

    /** Records every record handed to an exporter, in the order the exporter saw them. */
    private static final class RecordingExporter implements InsightExporter {
        final List<WorkflowInsightRecord> exported = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void export(WorkflowInsightRecord record) {
            exported.add(record);
        }
    }

    /** Holds every pump the scheduler starts until the test runs it, so the coalescing window is under test control. */
    private static final class HeldExecutor implements Executor {
        private final Queue<Runnable> pending = new ConcurrentLinkedQueue<>();

        @Override
        public void execute(Runnable command) {
            pending.add(command);
        }

        void runPending() {
            Runnable task;
            while ((task = pending.poll()) != null) {
                task.run();
            }
        }
    }

    /**
     * One invocation's plugin, wired to a scheduler whose pump the test controls, with an input transform that can be
     * armed to re-enter a hook of that same plugin. Re-entry through a content transform is the reachable path:
     * {@code buildRecord} calls the transform before it returns the record to be scheduled.
     */
    private static final class Fixture {
        final RecordingExporter exporter = new RecordingExporter();
        final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Runnable> armed = new AtomicReference<>();
        final ExportScheduler scheduler;
        final InsightPlugin plugin;

        Fixture(Executor executor) {
            var config = WorkflowInsightConfig.builder()
                    .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                    .content(ContentConfig.builder()
                            .inputTransform(value -> {
                                Runnable reentry = armed.getAndSet(null);
                                if (reentry != null) {
                                    reentry.run();
                                }
                                return value;
                            })
                            .build())
                    .addExporter(exporter)
                    .build();
            scheduler = new ExportScheduler(
                    List.of(exporter), (record, target) -> target.export(record), failures::add, executor);
            plugin = new InsightPlugin(new InsightSettings(config), scheduler, Executions.info(ARN));
        }

        /** Arms the next build's transform to run this once, before the build that triggered it finishes. */
        void arm(Runnable reentry) {
            armed.set(reentry);
        }

        boolean isDraining() {
            synchronized (scheduler) {
                return plugin.drainWaiters > 0;
            }
        }
    }

    // --- The two outcomes an unordered hand-off produces. ---

    @Test
    void anOvertakenBuildDoesNotOverwriteTheNewerRecordInTheSlot() {
        var executor = new HeldExecutor();
        var fixture = new Fixture(executor);

        // The start record claims the pump, which this executor holds, so every record below coalesces into the one
        // slot this invocation has.
        fixture.plugin.onInvocationStart(startInfo(operations(1)));
        // The change hook this arms builds a two-operation record and hands it over while the outer build below is
        // still inside its transform. The outer build then hands over its one-operation record last.
        fixture.arm(() -> fixture.plugin.onOperationChange(changeInfo(operations(2))));
        fixture.plugin.onOperationChange(changeInfo(operations(1)));

        executor.runPending();

        assertEquals(
                List.of(2),
                operationCounts(fixture),
                "the slot must hold the newest build's record; the overtaken build's older snapshot is dropped");
        assertEquals(List.of(), fixture.failures, "no scheduler failure was reported");
    }

    @Test
    void anOvertakenBuildIsNotExportedAfterTheRecordThatOvertookIt() {
        // Runnable::run makes the pump drain inside schedule(), so each record reaches the exporter before the next
        // hand-off. Nothing is coalesced, and a superseded record shows up as a stale export rather than a lost one.
        var fixture = new Fixture(Runnable::run);

        fixture.plugin.onInvocationStart(startInfo(operations(1)));
        fixture.arm(() -> fixture.plugin.onOperationChange(changeInfo(operations(2))));
        fixture.plugin.onOperationChange(changeInfo(operations(1)));

        assertEquals(
                List.of(1, 2),
                operationCounts(fixture),
                "the overtaken build's record must not be exported after the record that overtook it");
        assertEquals(List.of(), fixture.failures, "no scheduler failure was reported");
    }

    // --- The failure mode a revision check can introduce. ---

    @Test
    void theFinalRecordSurvivesANewerBuildStartedInsideIt() {
        var fixture = new Fixture(Runnable::run);

        fixture.plugin.onInvocationStart(startInfo(operations(1)));
        // Re-entered from the final record's own build, so the final record's revision is no longer the newest by the
        // time it is handed over. Dropping it would leave a RUNNING snapshot as this execution's last exported state.
        fixture.arm(() -> fixture.plugin.onOperationChange(changeInfo(operations(2))));
        fixture.plugin.onInvocationEnd(endInfo(operations(2)));

        var statuses = statuses(fixture);
        assertTrue(statuses.contains("SUCCEEDED"), "the final record must be exported; exported: " + statuses);
        assertEquals(
                "SUCCEEDED",
                statuses.get(statuses.size() - 1),
                "no RUNNING record may be exported after the final one; exported: " + statuses);
        assertEquals(List.of(), fixture.failures, "no scheduler failure was reported");
    }

    @Test
    void theFinalRecordIsTheOnlyRecordExportedWhenThePumpRunsAfterTheInvocationEnds() throws Exception {
        var executor = new HeldExecutor();
        var fixture = new Fixture(executor);

        // Runs the held pump only once the invocation end is inside its drain. Every record that end built is in the
        // slot by then, so which record is exported is decided by the slot rather than by when this thread wakes up.
        var invocationEnded = new AtomicBoolean();
        var pumper = new Thread(() -> {
            while (!fixture.isDraining()) {
                Thread.onSpinWait();
            }
            // Kept pumping until the hook returns: the flush the end requests after its drain needs a pump too.
            while (!invocationEnded.get()) {
                executor.runPending();
                Thread.onSpinWait();
            }
            executor.runPending();
        });
        pumper.setDaemon(true);
        pumper.start();

        fixture.plugin.onInvocationStart(startInfo(operations(1)));
        fixture.arm(() -> fixture.plugin.onOperationChange(changeInfo(operations(2))));
        fixture.plugin.onInvocationEnd(endInfo(operations(2)));
        invocationEnded.set(true);
        pumper.join(30_000);
        assertFalse(pumper.isAlive(), "the invocation end never completed its drain and flush");

        assertEquals(
                List.of("SUCCEEDED"),
                statuses(fixture),
                "the final record supersedes both RUNNING records in the slot and is the one exported");
        assertEquals(List.of(), fixture.failures, "no scheduler failure was reported");
    }

    // --- Fixture helpers. ---

    private static List<Integer> operationCounts(Fixture fixture) {
        List<Integer> counts = new ArrayList<>();
        synchronized (fixture.exporter.exported) {
            for (WorkflowInsightRecord record : fixture.exporter.exported) {
                counts.add(record.operations().size());
            }
        }
        return counts;
    }

    private static List<String> statuses(Fixture fixture) {
        List<String> statuses = new ArrayList<>();
        synchronized (fixture.exporter.exported) {
            for (WorkflowInsightRecord record : fixture.exporter.exported) {
                statuses.add(record.status());
            }
        }
        return statuses;
    }

    private static InvocationInfo startInfo(Map<String, OperationChangeItemInfo> operations) {
        return new InvocationInfo("req", ARN, true, START, INPUT, operations, Map.of());
    }

    private static OperationChangeInfo changeInfo(Map<String, OperationChangeItemInfo> operations) {
        return new OperationChangeInfo("req", ARN, operations, operations);
    }

    private static InvocationEndInfo endInfo(Map<String, OperationChangeItemInfo> operations) {
        return new InvocationEndInfo(
                "req", ARN, true, START, operations, InvocationStatus.SUCCEEDED, null, INPUT, "result");
    }

    /** A snapshot of {@code count} completed steps; the count is how a test tells two records apart. */
    private static Map<String, OperationChangeItemInfo> operations(int count) {
        Map<String, OperationChangeItemInfo> snapshot = new LinkedHashMap<>();
        for (int i = 1; i <= count; i++) {
            snapshot.put(
                    "op-" + i,
                    new OperationChangeItemInfo(
                            "op-" + i,
                            "step-" + i,
                            "STEP",
                            "Step",
                            null,
                            START.plusMillis(i),
                            START.plusMillis(i + 1),
                            OperationStatus.SUCCEEDED,
                            1,
                            false,
                            null,
                            null));
        }
        return snapshot;
    }
}
