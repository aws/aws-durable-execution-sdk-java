// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.lmi;

import com.amazonaws.services.lambda.runtime.Context;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import software.amazon.lambda.durable.serde.JacksonSerDes;

/** Observes Java stack boundaries, independently of logical future completion. */
final class InvocationTrace {
    static final String ENVIRONMENT = UUID.randomUUID().toString();
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicInteger LIVE_ROOTS = new AtomicInteger();
    private static final AtomicInteger LIVE_TASKS = new AtomicInteger();
    private static final AtomicInteger LIVE_WRAPPERS = new AtomicInteger();
    private static final JacksonSerDes JSON = new JacksonSerDes();
    final FixtureInput input;
    final String requestId;
    final String executionArn;
    final long deadlineNanos;
    final AtomicInteger tasks = new AtomicInteger();
    volatile boolean rootExited;
    volatile boolean wrapperReturned;

    InvocationTrace(FixtureInput input, Context context, String executionArn) {
        this.input = input;
        this.requestId = context.getAwsRequestId();
        this.executionArn = executionArn;
        this.deadlineNanos = System.nanoTime() + context.getRemainingTimeInMillis() * 1_000_000L;
    }

    void wrapperEnter() {
        LIVE_WRAPPERS.incrementAndGet();
        event("WRAPPER_ENTER");
    }

    void rootEnter() {
        LIVE_ROOTS.incrementAndGet();
        event("ROOT_ENTER");
    }

    void rootExit() {
        rootExited = true;
        LIVE_ROOTS.decrementAndGet();
        event("ROOT_EXIT");
    }

    void taskEnter(String name) {
        tasks.incrementAndGet();
        LIVE_TASKS.incrementAndGet();
        event("TASK_ENTER", Map.of("name", name));
    }

    void taskExit(String name) {
        tasks.decrementAndGet();
        LIVE_TASKS.decrementAndGet();
        event("TASK_EXIT", Map.of("name", name));
    }

    void wrapperExit(String status) {
        wrapperReturned = true;
        LIVE_WRAPPERS.decrementAndGet();
        event("WRAPPER_RETURN", Map.of("status", status));
    }

    void snapshot(ThreadPoolExecutor pool) {
        event(
                "SNAPSHOT",
                Map.of(
                        "active",
                        pool.getActiveCount(),
                        "queued",
                        pool.getQueue().size(),
                        "poolSize",
                        pool.getPoolSize(),
                        "threads",
                        ManagementFactory.getThreadMXBean().getThreadCount()));
    }

    void event(String kind) {
        event(kind, Map.of());
    }

    synchronized void event(String kind, Map<String, ?> details) {
        var data = new LinkedHashMap<String, Object>();
        data.put("kind", kind);
        data.put("environment", ENVIRONMENT);
        data.put("sequence", SEQUENCE.incrementAndGet());
        data.put("nanos", System.nanoTime());
        data.put("epochMillis", System.currentTimeMillis());
        data.put("remainingMillis", (deadlineNanos - System.nanoTime()) / 1_000_000);
        data.put("runId", input.runId());
        data.put("cohort", input.cohort());
        data.put("scenario", input.scenario());
        data.put("marker", input.marker());
        data.put("requestId", requestId);
        data.put("executionArn", executionArn);
        data.put("thread", Thread.currentThread().getName());
        data.put("tasks", tasks.get());
        data.put("rootExited", rootExited);
        data.put("wrapperReturned", wrapperReturned);
        data.put("liveRoots", LIVE_ROOTS.get());
        data.put("liveTasks", LIVE_TASKS.get());
        data.put("liveWrappers", LIVE_WRAPPERS.get());
        data.putAll(details);
        System.out.println("LMI_TEST " + JSON.serialize(data));
    }
}
