package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.checkpoint.CheckpointManager;
import com.amazonaws.lambda.durable.exception.NonDeterministicExecutionException;
import com.amazonaws.lambda.durable.execution.ExecutionCoordinator;
import com.amazonaws.lambda.durable.serde.SerDes;
import com.amazonaws.services.lambda.runtime.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class ReplayValidationTest {
    
    @Mock
    private CheckpointManager checkpointManager;
    
    @Mock
    private SerDes serDes;
    
    @Mock
    private Context lambdaContext;
    
    private DurableContext durableContext;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        var coordinator = new ExecutionCoordinator(checkpointManager);
        durableContext = new DurableContext(checkpointManager, serDes, lambdaContext, coordinator);
    }
    
    @Test
    void shouldPassValidationWhenNoCheckpointExists() {
        // Given: No existing operation
        when(checkpointManager.getOperation(anyString())).thenReturn(Optional.empty());
        when(serDes.serialize(anyString())).thenReturn("serialized");
        when(checkpointManager.checkpoint(any())).thenReturn(CompletableFuture.completedFuture(null));
        
        // When & Then: Should not throw
        assertDoesNotThrow(() -> 
            durableContext.step("test", String.class, () -> "result")
        );
    }
    
    @Test
    void shouldPassValidationWhenStepTypeAndNameMatch() {
        // Given: Existing STEP operation with matching name
        var existingOp = Operation.builder()
                .id("1")
                .name("test")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("serialized").build())
                .build();
        
        when(checkpointManager.getOperation("1")).thenReturn(Optional.of(existingOp));
        when(serDes.deserialize("serialized", String.class)).thenReturn("result");
        
        // When & Then: Should not throw
        assertDoesNotThrow(() -> 
            durableContext.step("test", String.class, () -> "result")
        );
    }
    
    @Test
    void shouldPassValidationWhenWaitTypeMatches() {
        // Given: Existing WAIT operation
        var existingOp = Operation.builder()
                .id("1")
                .type(OperationType.WAIT)
                .status(OperationStatus.SUCCEEDED)
                .build();
        
        when(checkpointManager.getOperation("1")).thenReturn(Optional.of(existingOp));
        
        // When & Then: Should not throw
        assertDoesNotThrow(() -> 
            durableContext.wait(Duration.ofSeconds(1))
        );
    }
    
    @Test
    void shouldThrowWhenOperationTypeMismatches() {
        // Given: Existing WAIT operation but current is STEP
        var existingOp = Operation.builder()
                .id("1")
                .name("test")
                .type(OperationType.WAIT)
                .status(OperationStatus.SUCCEEDED)
                .build();
        
        when(checkpointManager.getOperation("1")).thenReturn(Optional.of(existingOp));
        
        // When & Then: Should throw NonDeterministicExecutionException
        var exception = assertThrows(NonDeterministicExecutionException.class, () -> 
            durableContext.step("test", String.class, () -> "result")
        );
        
        assertTrue(exception.getMessage().contains("Operation type mismatch"));
        assertTrue(exception.getMessage().contains("Expected WAIT"));
        assertTrue(exception.getMessage().contains("got STEP"));
    }
    
    @Test
    void shouldThrowWhenOperationNameMismatches() {
        // Given: Existing STEP operation with different name
        var existingOp = Operation.builder()
                .id("1")
                .name("original")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("serialized").build())
                .build();
        
        when(checkpointManager.getOperation("1")).thenReturn(Optional.of(existingOp));
        
        // When & Then: Should throw NonDeterministicExecutionException
        var exception = assertThrows(NonDeterministicExecutionException.class, () -> 
            durableContext.step("changed", String.class, () -> "result")
        );
        
        assertTrue(exception.getMessage().contains("Operation name mismatch"));
        assertTrue(exception.getMessage().contains("Expected \"original\""));
        assertTrue(exception.getMessage().contains("got \"changed\""));
    }
    
    @Test
    void shouldHandleNullNamesCorrectly() {
        // Given: Existing STEP operation with null name
        var existingOp = Operation.builder()
                .id("1")
                .name(null)
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("serialized").build())
                .build();
        
        when(checkpointManager.getOperation("1")).thenReturn(Optional.of(existingOp));
        when(serDes.deserialize("serialized", String.class)).thenReturn("result");
        
        // When & Then: Should not throw when both names are null
        assertDoesNotThrow(() -> 
            durableContext.step(null, String.class, () -> "result")
        );
    }
    
    @Test
    void shouldThrowWhenNameChangesFromNullToValue() {
        // Given: Existing STEP operation with null name
        var existingOp = Operation.builder()
                .id("1")
                .name(null)
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("serialized").build())
                .build();
        
        when(checkpointManager.getOperation("1")).thenReturn(Optional.of(existingOp));
        
        // When & Then: Should throw when name changes from null to value
        var exception = assertThrows(NonDeterministicExecutionException.class, () -> 
            durableContext.step("newName", String.class, () -> "result")
        );

        assertTrue(exception.getMessage().contains("Operation name mismatch"));
        assertTrue(exception.getMessage().contains("Expected \"null\""));
        assertTrue(exception.getMessage().contains("got \"newName\""));
    }
    
    @Test
    void shouldThrowWhenNameChangesFromValueToNull() {
        // Given: Existing STEP operation with a name
        var existingOp = Operation.builder()
                .id("1")
                .name("existingName")
                .type(OperationType.STEP)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("serialized").build())
                .build();
        
        when(checkpointManager.getOperation("1")).thenReturn(Optional.of(existingOp));
        
        // When & Then: Should throw when name changes from value to null
        var exception = assertThrows(NonDeterministicExecutionException.class, () -> 
            durableContext.step(null, String.class, () -> "result")
        );

        assertTrue(exception.getMessage().contains("Operation name mismatch"));
        assertTrue(exception.getMessage().contains("Expected \"existingName\""));
        assertTrue(exception.getMessage().contains("got \"null\""));
    }
    
    @Test
    void shouldValidateStepAsyncOperations() {
        // Given: Existing WAIT operation but current is STEP (async)
        var existingOp = Operation.builder()
                .id("1")
                .name("test")
                .type(OperationType.WAIT)
                .status(OperationStatus.SUCCEEDED)
                .build();
        
        when(checkpointManager.getOperation("1")).thenReturn(Optional.of(existingOp));
        
        // When & Then: Should throw NonDeterministicExecutionException
        var exception = assertThrows(NonDeterministicExecutionException.class, () -> 
            durableContext.stepAsync("test", String.class, () -> "result")
        );

        assertTrue(exception.getMessage().contains("Operation type mismatch"));
        assertTrue(exception.getMessage().contains("Expected WAIT"));
        assertTrue(exception.getMessage().contains("got STEP"));
    }
    
    @Test
    void shouldSkipValidationWhenOperationTypeIsNull() {
        // Given: Existing operation with null type (edge case)
        var existingOp = Operation.builder()
                .id("1")
                .name("test")
                .type((OperationType) null)
                .status(OperationStatus.SUCCEEDED)
                .stepDetails(StepDetails.builder().result("serialized").build())
                .build();
        
        when(checkpointManager.getOperation("1")).thenReturn(Optional.of(existingOp));
        when(serDes.deserialize("serialized", String.class)).thenReturn("result");
        
        // When & Then: Should not throw (validation skipped)
        assertDoesNotThrow(() -> 
            durableContext.step("test", String.class, () -> "result")
        );
    }
}
