// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared executor for internal SDK coordination tasks.
 *
 * <p>This executor is used for SDK-internal operations such as checkpoint batching. It is separate from the
 * user-configured executor (via {@code DurableConfig}) which runs user-defined operations.
 *
 * <p>Using a dedicated thread pool ensures SDK coordination tasks are isolated from user code.
 */
final class InternalExecutor {

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    /**
     * Shared executor for all SDK-internal coordination tasks. Uses a cached thread pool that creates threads on
     * demand, reuses idle threads, and terminates threads after 60 seconds of inactivity by default.
     */
    static final Executor INSTANCE = Executors.newCachedThreadPool(runnable -> {
        var thread = new Thread(runnable, "durable-sdk-internal-" + THREAD_COUNTER.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    });

    private InternalExecutor() {
        // Utility class
    }
}
