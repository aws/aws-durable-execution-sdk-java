// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.execution;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.services.lambda.model.*;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TestUtils;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.exception.UnrecoverableDurableExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.plugin.*;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.util.ExceptionHelper;

@Timeout(15)
class OwnedExecutorDeliveryFailureTest {
    enum Cleanup {
        NONE,
        ORDINARY,
        FATAL,
        SAME_ERROR
    }

    static Stream<Arguments> cases() {
        return Stream.of(Cleanup.values())
                .flatMap(cleanup -> Stream.of(false, true).map(plugins -> Arguments.of(cleanup, plugins)));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void ownedCleanupRetainsSerializerFailureWithoutChangingWinner(Cleanup cleanup, boolean plugins) throws Exception {
        var delivery = new IllegalStateException("output serialization failed");
        var ordinary = new IllegalArgumentException("owned shutdown failed");
        var fatal = new InternalError("owned shutdown fatal");
        Throwable shutdownFailure = switch (cleanup) {
            case NONE -> null;
            case ORDINARY -> ordinary;
            case FATAL -> fatal;
            case SAME_ERROR -> delivery;
        };
        var bodies = new AtomicInteger();
        var serializations = new AtomicInteger();
        var shutdowns = new AtomicInteger();
        var ends = new AtomicInteger();
        var end = new AtomicReference<InvocationEndInfo>();
        var pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>()) {
            @Override
            public void shutdown() {
                shutdowns.incrementAndGet();
                super.shutdown();
                if (shutdownFailure != null) ExceptionHelper.sneakyThrow(shutdownFailure);
            }
        };
        pool.prestartAllCoreThreads();
        var builder = DurableConfig.builder()
                .withInvocationExecutorFactory(() -> pool)
                .withDurableExecutionClient(TestUtils.createMockClient())
                .withSerDes(new SerDes() {
                    public String serialize(Object value) {
                        serializations.incrementAndGet();
                        throw delivery;
                    }

                    @SuppressWarnings("unchecked")
                    public <T> T deserialize(String value, TypeToken<T> type) {
                        return (T) "input";
                    }
                });
        if (plugins)
            builder.withPlugins(ignored -> new DurableExecutionPlugin() {
                public void onInvocationEnd(InvocationEndInfo info) {
                    ends.incrementAndGet();
                    end.set(info);
                }
            });
        try {
            var observed = assertThrows(
                    Throwable.class,
                    () -> DurableExecutor.execute(
                            input(),
                            null,
                            TypeToken.get(String.class),
                            (value, context) -> {
                                bodies.incrementAndGet();
                                return "output";
                            },
                            builder.build()));
            if (cleanup == Cleanup.NONE) assertSame(delivery, observed);
            else if (cleanup == Cleanup.FATAL) assertSame(fatal, observed);
            else {
                var winner = assertInstanceOf(UnrecoverableDurableExecutionException.class, observed);
                assertEquals("Invocation executor shutdown failed", winner.getMessage());
                assertSame(shutdownFailure, winner.getCause());
            }
            assertTrue(contains(observed, delivery), "The completion-only serializer diagnostic must be retained");
            assertEquals(1, bodies.get());
            assertEquals(1, serializations.get());
            assertEquals(1, shutdowns.get());
            assertTrue(pool.isTerminated());
            assertEquals(plugins ? 1 : 0, ends.get());
            if (plugins) {
                assertEquals(InvocationStatus.RETRYING, end.get().invocationStatus());
                assertSame(delivery, end.get().executionError());
            }
            if (cleanup == Cleanup.SAME_ERROR)
                assertEquals(0, observed.getSuppressed().length, "Do not duplicate the known lifecycle cause");
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static boolean contains(Throwable root, Throwable expected) {
        var pending = new ArrayDeque<Throwable>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(root);
        while (!pending.isEmpty()) {
            var next = pending.remove();
            if (next == expected) return true;
            if (!seen.add(next)) continue;
            if (next.getCause() != null) pending.add(next.getCause());
            Collections.addAll(pending, next.getSuppressed());
        }
        return false;
    }

    private static DurableExecutionInput input() {
        return new DurableExecutionInput(
                "arn:execution/delivery",
                "token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(Operation.builder()
                                .id("delivery")
                                .type(OperationType.EXECUTION)
                                .status(OperationStatus.STARTED)
                                .startTimestamp(Instant.EPOCH)
                                .executionDetails(ExecutionDetails.builder()
                                        .inputPayload("\"input\"")
                                        .build())
                                .build())
                        .build());
    }
}
