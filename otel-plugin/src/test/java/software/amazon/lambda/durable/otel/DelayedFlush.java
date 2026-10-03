// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.otel;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class DelayedFlush implements SpanProcessor {
    public void onStart(Context parent, ReadWriteSpan span) {}

    final HandlerScopeFinalizationTest.Deadline deadline;
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger remainingAtFlush = new AtomicInteger();
    final AtomicLong flushMillis = new AtomicLong();
    final ScheduledExecutorService clock;

    DelayedFlush(HandlerScopeFinalizationTest.Deadline deadline, ScheduledExecutorService clock) {
        this.deadline = deadline;
        this.clock = clock;
    }

    public boolean isStartRequired() {
        return false;
    }

    public void onEnd(ReadableSpan span) {}

    public boolean isEndRequired() {
        return false;
    }

    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    public CompletableResultCode forceFlush() {
        calls.incrementAndGet();
        remainingAtFlush.set(deadline.remaining());
        var result = new CompletableResultCode();
        long start = System.nanoTime();
        clock.schedule(
                () -> {
                    flushMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    result.succeed();
                },
                250,
                TimeUnit.MILLISECONDS);
        return result;
    }
}
