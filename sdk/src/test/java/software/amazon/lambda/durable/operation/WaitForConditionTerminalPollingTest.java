// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.context.DurableContextImpl;
import software.amazon.lambda.durable.exception.IllegalDurableOperationException;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.model.WaitForConditionResult;

class WaitForConditionTerminalPollingTest {
    @ParameterizedTest
    @CsvSource({
        "CANCELLED,false",
        "CANCELLED,true",
        "TIMED_OUT,false",
        "TIMED_OUT,true",
        "STOPPED,false",
        "STOPPED,true"
    })
    void terminalObservationNeverRegistersAnotherPoll(String status, boolean alreadyKnown) throws Exception {
        var fixture = new Fixture(alreadyKnown ? status : "PENDING");
        var result = fixture.poll();
        if (!alreadyKnown) fixture.deliver(status);
        assertEquals(status, result.get(1, TimeUnit.SECONDS).statusAsString());
        assertEquals(alreadyKnown ? 0 : 1, fixture.polls.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NEW_UNKNOWN_STATUS"})
    @NullAndEmptySource
    void malformedStatusTerminatesInsteadOfPollingForever(String status) throws Exception {
        var fixture = new Fixture("PENDING");
        var result = fixture.poll();
        fixture.deliver(status);
        var failure = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalDurableOperationException.class, failure.getCause());
        verify(fixture.manager).terminateExecution((IllegalDurableOperationException) failure.getCause());
        assertEquals(1, fixture.polls.size());
    }

    @Test
    void nonterminalUpdatesStillWaitForReady() throws Exception {
        var fixture = new Fixture("PENDING");
        var result = fixture.poll();
        fixture.deliver("STARTED");
        assertFalse(result.isDone());
        fixture.deliver("PENDING");
        assertFalse(result.isDone());
        fixture.deliver("READY");
        assertEquals("READY", result.get(1, TimeUnit.SECONDS).statusAsString());
        assertEquals(3, fixture.polls.size());
    }

    private static final class Fixture {
        final ExecutionManager manager = mock(ExecutionManager.class);
        final AtomicReference<Operation> known;
        final List<CompletableFuture<Operation>> polls = new ArrayList<>();
        final WaitForConditionOperation<Integer> operation;

        Fixture(String status) {
            known = new AtomicReference<>(snapshot(status));
            var context = mock(DurableContextImpl.class);
            when(context.getExecutionManager()).thenReturn(manager);
            when(context.getDurableConfig()).thenReturn(DurableConfig.builder().build());
            when(manager.getOperationAndUpdateReplayState("condition")).thenAnswer(call -> known.get());
            when(manager.pollForOperationUpdates("condition")).thenAnswer(call -> {
                var poll = new CompletableFuture<Operation>();
                polls.add(poll);
                return poll;
            });
            operation = new WaitForConditionOperation<>(
                    OperationIdentifier.of("condition", "condition", OperationSubType.WAIT_FOR_CONDITION),
                    (state, contextIgnored) -> WaitForConditionResult.stopPolling(state),
                    TypeToken.get(Integer.class),
                    WaitForConditionConfig.<Integer>builder().initialState(1).build(),
                    context);
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<Operation> poll() throws Exception {
            var method = WaitForConditionOperation.class.getDeclaredMethod("pollUntilReady");
            method.setAccessible(true);
            return (CompletableFuture<Operation>) method.invoke(operation);
        }

        void deliver(String status) {
            var update = snapshot(status);
            known.set(update);
            polls.get(polls.size() - 1).complete(update);
        }

        private static Operation snapshot(String status) {
            return Operation.builder()
                    .id("condition")
                    .type(OperationType.STEP)
                    .status(status)
                    .build();
        }
    }
}
