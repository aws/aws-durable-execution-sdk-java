// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.insight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;

class InsightFatalFailureTest {
    private static final String ARN = "arn:aws:lambda:us-west-2:1:function:f:$LATEST/durable-execution/e/i";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");

    private static final class TestVmError extends VirtualMachineError {
        TestVmError() {
            super("deterministic VM failure");
        }
    }

    @SuppressWarnings("removal")
    private static Error fatal(String kind) {
        return "vm".equals(kind) ? new TestVmError() : new ThreadDeath();
    }

    private static RuntimeException wrapped(Error fatal) {
        return new CompletionException(new ExecutionException(fatal));
    }

    private static InvocationEndInfo end() {
        return new InvocationEndInfo("r", ARN, true, START, Map.of(), InvocationStatus.SUCCEEDED, null, null, "output");
    }

    @ParameterizedTest
    @CsvSource({"vm,false", "vm,true", "thread,false", "thread,true"})
    void outputTransformFatalEscapesInvocationEnd(String kind, boolean wrap) {
        var failure = fatal(kind);
        Function<Object, Object> transform = value -> {
            if (wrap) throw wrapped(failure);
            throw failure;
        };
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(WorkflowInsightConfig.builder()
                        .content(ContentConfig.builder()
                                .outputTransform(transform)
                                .build())
                        .build()),
                ARN,
                START);
        plugin.onInvocationStart(Executions.info(ARN));
        assertSame(failure, assertThrows(Error.class, () -> plugin.onInvocationEnd(end())));
        assertFalse(Executions.outstanding(plugin));
    }

    @ParameterizedTest
    @CsvSource({"vm,false", "vm,true", "thread,false", "thread,true"})
    void asynchronousExporterFatalReachesInvocationEnd(String kind, boolean wrap) {
        var failure = fatal(kind);
        InsightExporter exporter = record -> {
            if (wrap) throw wrapped(failure);
            throw failure;
        };
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(
                        WorkflowInsightConfig.builder().addExporter(exporter).build()),
                ARN,
                START);
        plugin.onInvocationStart(Executions.info(ARN));
        assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertSame(failure, assertThrows(Error.class, () -> plugin.onInvocationEnd(end()))));
        assertFalse(Executions.outstanding(plugin));
        assertSame(failure, assertThrows(Error.class, plugin::drainExports), "a finished worker cannot hide its fatal");
    }

    @ParameterizedTest
    @CsvSource({"vm,false", "vm,true", "thread,false", "thread,true"})
    void flushFatalReachesInvocationEnd(String kind, boolean wrap) {
        var failure = fatal(kind);
        var exporter = new InsightExporter() {
            @Override
            public void export(WorkflowInsightRecord record) {}

            @Override
            public void flush() {
                if (wrap) throw wrapped(failure);
                throw failure;
            }
        };
        var plugin = Executions.plugin(
                WorkflowInsight.workflowInsight(
                        WorkflowInsightConfig.builder().addExporter(exporter).build()),
                ARN,
                START);
        assertTimeoutPreemptively(
                Duration.ofSeconds(3),
                () -> assertSame(failure, assertThrows(Error.class, () -> plugin.onInvocationEnd(end()))));
        assertFalse(Executions.outstanding(plugin));
    }

    @Test
    void fatalFanOutReleasesCallerAndQueuedInvocationWithoutWaitingForBlockedPeer() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var failure = new TestVmError();
        InsightExporter blocked = record -> {
            entered.countDown();
            try {
                assertTrue(release.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        };
        InsightExporter throwing = record -> {
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            throw wrapped(failure);
        };
        var scheduler = new ExportScheduler(List.of(blocked, throwing), WorkflowInsight::exportRecord, ignored -> {});
        var first = Executions.plugin(scheduler, ARN);
        var queued = Executions.plugin(scheduler, ARN + "2");
        var record = new WorkflowInsightRecord();
        record.executionArn = ARN;
        try {
            synchronized (scheduler) {
                scheduler.schedule(first, record);
                scheduler.schedule(queued, record);
            }
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> assertSame(failure, assertThrows(Error.class, () -> scheduler.drain(first))));
            assertFalse(Executions.outstanding(first));
            assertFalse(Executions.outstanding(queued));
            assertEquals(0, scheduler.retainedInvocationCount());
            assertSame(failure, assertThrows(Error.class, scheduler::flush));
        } finally {
            release.countDown();
        }
    }

    @Test
    void ordinaryApplicationExceptionWithFatalAsDataRemainsFailOpen() {
        var record = new WorkflowInsightRecord();
        assertEquals(null, WorkflowInsight.applyDataContent("input", "value", true, value -> {
            throw new IllegalStateException("business error", new TestVmError());
        }));
        WorkflowInsight.exportRecord(record, value -> {
            throw new AssertionError("ordinary plugin error");
        });
    }
}
