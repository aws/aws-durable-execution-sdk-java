// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.ThreadContext;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;

class OperationOwnerInterruptionTest {
    @Test
    void completingOlderAttemptCannotLoseNewOwnerOrInterruptReusedWorker() throws Exception {
        var firstOwner = new AtomicReference<Thread>();
        var firstExited = new CountDownLatch(1);
        var firstRelease = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var secondRelease = new CountDownLatch(1);
        var secondInterrupted = new CountDownLatch(1);
        var unrelatedEntered = new CountDownLatch(1);
        var unrelatedRelease = new CountDownLatch(1);
        var unrelatedInterrupted = new AtomicBoolean();
        var workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>()) {
            @Override
            protected void afterExecute(Runnable task, Throwable failure) {
                if (Thread.currentThread() == firstOwner.get()) firstExited.countDown();
            }
        };
        try {
            var context = mock(DurableContextImpl.class);
            var manager = mock(ExecutionManager.class);
            when(context.getExecutionManager()).thenReturn(manager);
            when(manager.getCurrentThreadContext()).thenReturn(new ThreadContext("op", ThreadType.STEP));
            when(context.getDurableConfig())
                    .thenReturn(
                            DurableConfig.builder().withExecutorService(workers).build());
            var operation = new TestOperation(context);
            operation.run(() -> {
                firstOwner.set(Thread.currentThread());
                await(firstRelease);
            });
            var first = operation.getRunningUserHandler();
            first.whenComplete((ignored, failure) -> {
                operation.run(() -> {
                    secondEntered.countDown();
                    try {
                        secondRelease.await();
                    } catch (InterruptedException expected) {
                        secondInterrupted.countDown();
                    }
                });
                await(secondEntered);
            });
            firstRelease.countDown();
            assertTrue(firstExited.await(3, TimeUnit.SECONDS));
            assertTrue(secondEntered.await(3, TimeUnit.SECONDS));
            var unrelated = workers.submit(() -> {
                assertSame(firstOwner.get(), Thread.currentThread());
                unrelatedEntered.countDown();
                try {
                    unrelatedRelease.await();
                } catch (InterruptedException unexpected) {
                    unrelatedInterrupted.set(true);
                }
            });
            assertTrue(unrelatedEntered.await(3, TimeUnit.SECONDS));
            operation.interruptRunningUserHandler();
            assertTrue(secondInterrupted.await(1, TimeUnit.SECONDS), "the second attempt remains the registered owner");
            operation.getRunningUserHandler().get(1, TimeUnit.SECONDS);
            operation.interruptRunningUserHandler();
            assertFalse(unrelatedInterrupted.get(), "an idle/completed attempt cannot interrupt a reused worker");
            unrelatedRelease.countDown();
            unrelated.get(1, TimeUnit.SECONDS);
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
            unrelatedRelease.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void completedAttemptIsNotInterruptedWhileItsCompletionCallbackIsStillRunning() throws Exception {
        var releaseBody = new CountDownLatch(1);
        var callbackEntered = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var callbackExited = new CountDownLatch(1);
        var callbackInterrupted = new AtomicBoolean();
        var workers = Executors.newSingleThreadExecutor();
        try {
            var context = mock(DurableContextImpl.class);
            var manager = mock(ExecutionManager.class);
            when(context.getExecutionManager()).thenReturn(manager);
            when(manager.getCurrentThreadContext()).thenReturn(new ThreadContext("op", ThreadType.STEP));
            when(context.getDurableConfig())
                    .thenReturn(
                            DurableConfig.builder().withExecutorService(workers).build());
            var operation = new TestOperation(context);
            operation.run(() -> await(releaseBody));
            var completion = operation.getRunningUserHandler();
            completion.whenComplete((ignored, failure) -> {
                callbackEntered.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException unexpected) {
                    callbackInterrupted.set(true);
                } finally {
                    callbackExited.countDown();
                }
            });
            releaseBody.countDown();
            assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
            assertTrue(completion.isDone());
            operation.interruptRunningUserHandler();
            releaseCallback.countDown();
            assertTrue(callbackExited.await(3, TimeUnit.SECONDS));
            workers.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertFalse(callbackInterrupted.get(), "completed ownership must not authorize a later interrupt");
        } finally {
            releaseBody.countDown();
            releaseCallback.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class TestOperation extends BaseDurableOperation {
        TestOperation(DurableContextImpl context) {
            super(OperationIdentifier.of("op", "op", OperationSubType.STEP), context, null);
        }

        @Override
        protected void start() {}

        @Override
        protected void replay(Operation existing) {}

        void run(Runnable action) {
            runUserHandler(action, ThreadType.STEP);
        }
    }
}
