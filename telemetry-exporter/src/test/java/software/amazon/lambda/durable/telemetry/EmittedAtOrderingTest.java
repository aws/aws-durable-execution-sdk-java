// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.plugin.InvocationEndInfo;
import software.amazon.lambda.durable.plugin.InvocationInfo;
import software.amazon.lambda.durable.plugin.InvocationStatus;
import software.amazon.lambda.durable.plugin.OperationChangeInfo;

/**
 * {@code emittedAt} strictly increases within one plugin instance.
 *
 * <p>{@code emittedAt} has millisecond precision. With {@code ON_CHANGE}, two records can be built in the same
 * millisecond. The SQS FIFO deduplication id and the default DynamoDB sort key use {@code executionArn} and
 * {@code emittedAt}. So two equal values drop or overwrite a record.
 */
class EmittedAtOrderingTest {

    private static final String ARN =
            "arn:aws:lambda:us-west-2:111122223333:function:f:$LATEST/durable-execution/exec-1/invocation-1";
    private static final Instant START = Instant.parse("2026-10-23T18:42:40Z");
    private static final long T123 = Instant.parse("2026-10-23T18:42:44.123Z").toEpochMilli();

    private static final class Fixture {
        final List<DurableExecutionTelemetryRecord> exported = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong clock = new AtomicLong(T123);
        final TelemetryPlugin plugin;

        Fixture() {
            var config = DurableExecutionTelemetryConfig.builder()
                    .emitMode(DurableExecutionTelemetryConfig.EmitMode.ON_CHANGE)
                    .addExporter(exported::add)
                    .build();
            // Runnable::run runs the pump inside each hand-off. So every record reaches the exporter before the next
            // record is built, and the scheduler coalesces none of them.
            var scheduler = new ExportScheduler(
                    config.exporters(), (record, target) -> target.export(record), t -> {}, Runnable::run);
            plugin = new TelemetryPlugin(
                    new TelemetrySettings(config),
                    scheduler,
                    new InvocationInfo("req", ARN, true, START, null, Map.of(), Map.of()),
                    clock::get);
        }

        void start() {
            plugin.onInvocationStart(new InvocationInfo("req", ARN, true, START, "in", Map.of(), Map.of()));
        }

        void change() {
            plugin.onOperationChange(new OperationChangeInfo("req", ARN, Map.of(), Map.of()));
        }

        void end() {
            plugin.onInvocationEnd(new InvocationEndInfo(
                    "req", ARN, true, START, Map.of(), InvocationStatus.SUCCEEDED, null, "in", "out"));
        }

        List<String> statusAndEmittedAt() {
            List<String> out = new ArrayList<>();
            synchronized (exported) {
                for (var record : exported) {
                    out.add(record.status() + " " + record.emittedAt);
                }
            }
            return out;
        }
    }

    @Test
    void recordsBuiltInOneMillisecondGetIncreasingValues() {
        var fixture = new Fixture();

        fixture.start();
        fixture.change();
        fixture.end();

        assertEquals(
                List.of(
                        "RUNNING 2026-10-23T18:42:44.123Z",
                        "RUNNING 2026-10-23T18:42:44.124Z",
                        "SUCCEEDED 2026-10-23T18:42:44.125Z"),
                fixture.statusAndEmittedAt());
    }

    @Test
    void theClockIsUsedOnceItPassesTheLastValue() {
        var fixture = new Fixture();

        fixture.start();
        fixture.clock.set(T123 + 377);
        fixture.change();
        // The clock moves backwards. The last value plus 1 ms is used.
        fixture.clock.set(T123 + 100);
        fixture.end();

        assertEquals(
                List.of(
                        "RUNNING 2026-10-23T18:42:44.123Z",
                        "RUNNING 2026-10-23T18:42:44.500Z",
                        "SUCCEEDED 2026-10-23T18:42:44.501Z"),
                fixture.statusAndEmittedAt());
    }

    @Test
    void concurrentCallsGetDistinctValues() throws Exception {
        var fixture = new Fixture();
        int threads = 8;
        int callsPerThread = 2_000;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    List<String> values = new ArrayList<>(callsPerThread);
                    ready.countDown();
                    go.await();
                    for (int i = 0; i < callsPerThread; i++) {
                        values.add(fixture.plugin.nextEmittedAt());
                    }
                    return values;
                }));
            }
            ready.await();
            go.countDown();
            Set<String> all = new HashSet<>();
            for (var future : futures) {
                all.addAll(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(threads * callsPerThread, all.size(), "every emittedAt must be distinct");
        } finally {
            pool.shutdownNow();
        }
    }
}
