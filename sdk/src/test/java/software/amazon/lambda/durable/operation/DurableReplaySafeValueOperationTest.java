// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.context.BaseContextImpl;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionStepConfig;
import software.amazon.lambda.durable.extension.ExtensionStepFunction;
import software.amazon.lambda.durable.extension.ExtensionStepResult;

class DurableReplaySafeValueOperationTest {
    @AfterEach
    void clearContext() {
        BaseContextImpl.setCurrentContext(null);
    }

    @Test
    void defaultHelpersUseExtensionStepsWithDescriptiveNamesAndSubtypes() {
        var context = mockDurableContext();
        var uuidReservation = mock(ExtensionOperation.class);
        var nowReservation = mock(ExtensionOperation.class);
        var randomReservation = mock(ExtensionOperation.class);
        var uuid = UUID.fromString("12345678-1234-5678-1234-567812345678");
        var now = Instant.parse("2026-09-01T12:00:00Z");
        BaseContextImpl.setCurrentContext(context);
        when(context.reserve("uuid")).thenReturn(uuidReservation);
        when(context.reserve("now")).thenReturn(nowReservation);
        when(context.reserve("random")).thenReturn(randomReservation);
        when(uuidReservation.stepAsync(
                        eq("UUID"), eq(TypeToken.get(UUID.class)), any(ExtensionStepFunction.class), any()))
                .thenReturn(CompletableFuture.completedFuture(uuid));
        when(nowReservation.stepAsync(
                        eq("Now"), eq(TypeToken.get(Instant.class)), any(ExtensionStepFunction.class), any()))
                .thenReturn(CompletableFuture.completedFuture(now));
        when(randomReservation.stepAsync(
                        eq("Random"), eq(TypeToken.get(Double.class)), any(ExtensionStepFunction.class), any()))
                .thenReturn(CompletableFuture.completedFuture(0.25));

        assertEquals(uuid, DurableReplaySafeValueOperation.uuid());
        assertEquals(now, DurableReplaySafeValueOperation.now());
        assertEquals(0.25, DurableReplaySafeValueOperation.random());

        verify(uuidReservation)
                .stepAsync(eq("UUID"), eq(TypeToken.get(UUID.class)), any(ExtensionStepFunction.class), any());
        verify(nowReservation)
                .stepAsync(eq("Now"), eq(TypeToken.get(Instant.class)), any(ExtensionStepFunction.class), any());
        verify(randomReservation)
                .stepAsync(eq("Random"), eq(TypeToken.get(Double.class)), any(ExtensionStepFunction.class), any());
    }

    @Test
    void namedAsyncHelpersGenerateExpectedValueTypesAndUseDefaultStepRetries() {
        var context = mockDurableContext();
        BaseContextImpl.setCurrentContext(context);

        assertGeneratedValue(context, "request-id", "UUID", UUID.class, UUID.class);
        assertGeneratedValue(context, "created-at", "Now", Instant.class, Instant.class);
        var random = assertGeneratedValue(context, "sample", "Random", Double.class, Double.class);
        assertTrue((Double) random >= 0.0);
        assertTrue((Double) random < 1.0);
    }

    @Test
    void helpersRequireAnActiveDurableContext() {
        assertThrows(IllegalStateException.class, DurableReplaySafeValueOperation::uuid);
        assertThrows(IllegalStateException.class, DurableReplaySafeValueOperation::now);
        assertThrows(IllegalStateException.class, DurableReplaySafeValueOperation::random);
    }

    private <T> Object assertGeneratedValue(
            ExtensionContext context, String name, String subType, Class<T> resultType, Class<?> expectedValueType) {
        var reservation = mock(ExtensionOperation.class);
        when(context.reserve(name)).thenReturn(reservation);
        when(reservation.stepAsync(eq(subType), eq(TypeToken.get(resultType)), any(ExtensionStepFunction.class), any()))
                .thenReturn(new CompletableFuture<>());

        switch (subType) {
            case "UUID" -> DurableReplaySafeValueOperation.uuidAsync(name);
            case "Now" -> DurableReplaySafeValueOperation.nowAsync(name);
            case "Random" -> DurableReplaySafeValueOperation.randomAsync(name);
            default -> throw new AssertionError("Unexpected subtype: " + subType);
        }

        @SuppressWarnings("unchecked")
        var function = (ArgumentCaptor<ExtensionStepFunction<T>>)
                (ArgumentCaptor<?>) ArgumentCaptor.forClass(ExtensionStepFunction.class);
        var config = ArgumentCaptor.forClass(ExtensionStepConfig.class);
        verify(reservation).stepAsync(eq(subType), eq(TypeToken.get(resultType)), function.capture(), config.capture());
        assertNotNull(config.getValue().retryStrategy());

        var outcome = assertInstanceOf(
                ExtensionStepResult.Succeeded.class,
                function.getValue().apply(null).toCompletableFuture().join());
        return assertInstanceOf(expectedValueType, outcome.value());
    }

    private ExtensionContext mockDurableContext() {
        return (ExtensionContext) mock(
                DurableContext.class,
                withSettings().extraInterfaces(ExtensionContext.class).defaultAnswer(CALLS_REAL_METHODS));
    }
}
