// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.insight.internal.FatalErrors;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;
import software.amazon.lambda.durable.plugin.PluginRunner;

class InsightFatalBoundaryTest {
    private static final String ARN = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/e/i";

    public static final class FatalPayload {
        private final Error failure;

        FatalPayload(Error failure) {
            this.failure = failure;
        }

        public String getValue() {
            throw failure;
        }
    }

    private static final class TestVmError extends VirtualMachineError {}

    @SuppressWarnings("removal")
    private static Error fatal(String kind) {
        return "vm".equals(kind) ? new TestVmError() : new ThreadDeath();
    }

    private static RuntimeException transport(Error error) {
        return new CompletionException(
                new ExecutionException(new UndeclaredThrowableException(new InvocationTargetException(error))));
    }

    @ParameterizedTest
    @CsvSource({"vm,false", "vm,true", "thread,false", "thread,true"})
    void resultRedactorPropagatesFatalWithoutReplacingIdentity(String kind, boolean wrapped) {
        var failure = fatal(kind);
        assertSame(
                failure,
                assertThrows(
                        Error.class,
                        () -> WorkflowInsight.applyResultOverride(
                                value -> {
                                    if (wrapped) throw transport(failure);
                                    throw failure;
                                },
                                "{\"private\":true}")));
    }

    @ParameterizedTest
    @CsvSource({"vm", "thread"})
    void jacksonGetterFatalEscapesSnapshotAndSerialization(String kind) {
        var failure = fatal(kind);
        var value = new FatalPayload(failure);
        assertSame(failure, assertThrows(Error.class, () -> Json.deepCopyContent(value)));
        assertSame(failure, assertThrows(Error.class, () -> Json.stringify(value)));
        assertSame(failure, assertThrows(Error.class, () -> Json.prettyStringify(value)));
        assertSame(failure, assertThrows(Error.class, () -> Json.byteSize(value)));
        var info = new InvocationInfo("r", ARN, true, Instant.now(), value, Map.of(), Map.of());
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder().build()), info);
        assertSame(failure, assertThrows(Error.class, () -> plugin.onInvocationStart(info)));
        assertFalse(Executions.outstanding(plugin));
    }

    @ParameterizedTest
    @CsvSource({"vm", "thread"})
    void rendererFatalReachesTheBoundary(String kind) {
        var failure = fatal(kind);
        var exporter = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public Integer maxRecordSizeBytes() {
                return 1;
            }

            @Override
            public Object render(WorkflowInsightRecord record) {
                throw transport(failure);
            }
        };
        assertSame(
                failure,
                assertThrows(Error.class, () -> WorkflowInsight.exportRecord(new WorkflowInsightRecord(), exporter)));
    }

    @Test
    void finishedFatalPumpLeavesExceptionalSignalAndCannotAcceptMoreWork() {
        var failure = new TestVmError();
        var tasks = new ArrayList<Runnable>();
        InsightExporter exporter = record -> {
            throw transport(failure);
        };
        var scheduler =
                new ExportScheduler(List.of(exporter), WorkflowInsight::exportRecord, ignored -> {}, tasks::add);
        var owner = Executions.plugin(scheduler, ARN);
        scheduler.schedule(owner, new WorkflowInsightRecord());
        var signal = owner.settled;
        assertSame(failure, assertThrows(Error.class, () -> tasks.remove(0).run()));
        assertTrue(signal.isCompletedExceptionally());
        assertSame(
                failure, assertThrows(CompletionException.class, signal::join).getCause());
        assertFalse(Executions.outstanding(owner));
        assertSame(failure, assertThrows(Error.class, () -> scheduler.drain(owner)));
        var later = Executions.plugin(scheduler, ARN + "later");
        assertSame(failure, assertThrows(Error.class, () -> scheduler.schedule(later, new WorkflowInsightRecord())));
        assertFalse(Executions.outstanding(later));
        assertEquals(0, scheduler.retainedInvocationCount());
    }

    @Test
    void fatalExecutorSubmissionReleasesQueuedOwner() {
        var failure = new TestVmError();
        var scheduler = new ExportScheduler(List.of(), (record, exporter) -> {}, ignored -> {}, command -> {
            throw transport(failure);
        });
        var owner = Executions.plugin(scheduler, ARN);
        assertSame(failure, assertThrows(Error.class, () -> scheduler.schedule(owner, new WorkflowInsightRecord())));
        assertFalse(Executions.outstanding(owner));
        assertEquals(0, scheduler.retainedInvocationCount());
    }

    @Test
    void fatalFailureHandlerCannotTurnExporterFailureIntoSuccess() {
        var failure = new TestVmError();
        var tasks = new ArrayList<Runnable>();
        var scheduler = new ExportScheduler(
                List.of(record -> {}),
                (record, exporter) -> {
                    throw new AssertionError("ordinary exporter error");
                },
                ignored -> {
                    throw transport(failure);
                },
                tasks::add);
        var owner = Executions.plugin(scheduler, ARN);
        scheduler.schedule(owner, new WorkflowInsightRecord());
        assertSame(failure, assertThrows(Error.class, () -> tasks.remove(0).run()));
        assertSame(failure, assertThrows(Error.class, scheduler::flush));
        assertFalse(Executions.outstanding(owner));
    }

    @Test
    void transportCyclesAreBoundedAndBusinessCausesAreNotUnwrapped() {
        class Cycle extends CompletionException {}
        var left = new Cycle();
        var right = new Cycle();
        left.initCause(right);
        right.initCause(left);
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertNull(FatalErrors.find(left)));
        assertNull(FatalErrors.find(new IllegalStateException("business wrapper", new TestVmError())));
        assertDoesNotThrow(
                () -> WorkflowInsight.logSafely("ordinary linkage error", new NoClassDefFoundError("optional")));
    }

    @Test
    void observedBackgroundFatalPreventsLaterHooksFromRunningTransforms() {
        var failure = new TestVmError();
        var outputCalls = new AtomicInteger();
        InsightExporter exporter = record -> {
            throw failure;
        };
        var info = new InvocationInfo("r", ARN, true, Instant.now(), null, Map.of(), Map.of());
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                        .emitMode(WorkflowInsightConfig.EmitMode.ON_CHANGE)
                        .content(ContentConfig.builder()
                                .outputTransform(value -> {
                                    outputCalls.incrementAndGet();
                                    return value;
                                })
                                .build())
                        .addExporter(exporter)
                        .build()),
                info);
        plugin.onInvocationStart(info);
        assertSame(failure, assertThrows(Error.class, plugin::drainExports));
        assertSame(
                failure,
                assertThrows(
                        Error.class,
                        () -> plugin.onOperationChange(new OperationChangeInfo("r", ARN, Map.of(), Map.of()))));
        var end = new InvocationEndInfo(
                "r", ARN, true, info.executionStartTime(), Map.of(), InvocationStatus.SUCCEEDED, null, null, "output");
        assertSame(failure, assertThrows(Error.class, () -> plugin.onInvocationEnd(end)));
        assertEquals(0, outputCalls.get());
        assertFalse(Executions.outstanding(plugin));
    }

    @ParameterizedTest
    @CsvSource({"vm", "thread"})
    void sdkPluginRunnerObservesTheAsynchronousExporterFatal(String kind) {
        var failure = fatal(kind);
        InsightExporter exporter = record -> {
            throw transport(failure);
        };
        var factory = WorkflowInsight.workflowInsight(
                WorkflowInsightConfig.builder().addExporter(exporter).build());
        var runner = new PluginRunner(List.of(factory));
        var start = new InvocationInfo("r", ARN, true, Instant.now(), null, Map.of(), Map.of());
        runner.onInvocationStart(start);
        var end = new InvocationEndInfo(
                "r", ARN, true, start.executionStartTime(), Map.of(), InvocationStatus.SUCCEEDED, null, null, "result");
        assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertSame(failure, assertThrows(Error.class, () -> runner.onInvocationEnd(end))));
    }
}
