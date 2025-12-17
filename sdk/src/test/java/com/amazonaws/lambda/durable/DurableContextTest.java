package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.checkpoint.CheckpointManager;
import com.amazonaws.lambda.durable.checkpoint.SuspendExecutionException;
import com.amazonaws.lambda.durable.execution.ExecutionCoordinator;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.testing.LocalMemoryExecutionClient;
import com.amazonaws.lambda.durable.checkpoint.ExecutionState;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class DurableContextTest {
    
    private DurableContext createTestContext() {
        var client = new LocalMemoryExecutionClient();
        var state = new ExecutionState("arn", "token", List.of());
        var executor = Executors.newSingleThreadExecutor();
        var checkpointManager = new CheckpointManager(state, client, executor);
        var coordinator = new com.amazonaws.lambda.durable.execution.ExecutionCoordinator(checkpointManager);
        var serDes = new JacksonSerDes();
        return new DurableContext(checkpointManager, serDes, null, coordinator);
    }
    
    @Test
    void testContextCreation() {
        var context = createTestContext();
        
        assertNotNull(context);
        assertNull(context.getLambdaContext());
    }
    
    @Test
    void testStepExecution() {
        var context = createTestContext();
        
        var result = context.step("test", String.class, () -> "Hello World");
        
        assertEquals("Hello World", result);
    }
    
    @Test
    void testStepReplay() {
        // Create context with existing operation
        var client = new LocalMemoryExecutionClient();
        var existingOp = Operation.builder()
                .id("1")
                .status(software.amazon.awssdk.services.lambda.model.OperationStatus.SUCCEEDED)
                .stepDetails(software.amazon.awssdk.services.lambda.model.StepDetails.builder()
                        .result("\"Cached Result\"")
                        .build())
                .build();
        
        var state = new ExecutionState("arn", "token", List.of(existingOp));
        var executor = Executors.newSingleThreadExecutor();
        var checkpointManager = new CheckpointManager(state, client, executor);
        var coordinator = new ExecutionCoordinator(checkpointManager);
        var serDes = new JacksonSerDes();
        var context = new DurableContext(checkpointManager, serDes, null, coordinator);
        
        // This should return cached result, not execute the function
        var result = context.step("test", String.class, () -> "New Result");
        
        assertEquals("Cached Result", result);
    }
    
    @Test
    void testStepAsync() throws Exception {
        var context = createTestContext();
        
        var future = context.stepAsync("async-test", String.class, () -> "Async Result");
        
        assertNotNull(future);
        assertEquals("Async Result", future.get());
    }
    
    @Test
    void testStepAsyncReplay() throws Exception {
        // Create context with existing operation
        var client = new LocalMemoryExecutionClient();
        var existingOp = Operation.builder()
                .id("1")
                .status(software.amazon.awssdk.services.lambda.model.OperationStatus.SUCCEEDED)
                .stepDetails(software.amazon.awssdk.services.lambda.model.StepDetails.builder()
                        .result("\"Cached Async Result\"")
                        .build())
                .build();
        
        var state = new ExecutionState("arn", "token", List.of(existingOp));
        var executor = Executors.newSingleThreadExecutor();
        var checkpointManager = new CheckpointManager(state, client, executor);
        var coordinator = new ExecutionCoordinator(checkpointManager);
        var serDes = new JacksonSerDes();
        var context = new DurableContext(checkpointManager, serDes, null, coordinator);
        
        // This should return cached result immediately
        var future = context.stepAsync("async-test", String.class, () -> "New Async Result");
        
        assertTrue(future.isDone());
        assertEquals("Cached Async Result", future.get());
    }
    
    @Test
    void testWait() {
        var context = createTestContext();
        
        // Wait should throw SuspendExecutionException
        assertThrows(SuspendExecutionException.class, () -> {
            context.wait(Duration.ofMinutes(5));
        });
    }
    
    @Test
    void testWaitReplay() {
        // Create context with completed wait operation
        var client = new LocalMemoryExecutionClient();
        var existingOp = Operation.builder()
                .id("1")
                .status(OperationStatus.SUCCEEDED)
                .build();
        
        var state = new ExecutionState("arn", "token", List.of(existingOp));
        var executor = Executors.newSingleThreadExecutor();
        var checkpointManager = new CheckpointManager(state, client, executor);
        var coordinator = new ExecutionCoordinator(checkpointManager);
        var serDes = new JacksonSerDes();
        var context = new DurableContext(checkpointManager, serDes, null, coordinator);
        
        // Wait should complete immediately (no exception)
        assertDoesNotThrow(() -> {
            context.wait(Duration.ofMinutes(5));
        });
    }
    
    @Test
    void testCombinedSyncAsyncWait() throws Exception {
        var context = createTestContext();
        
        // Execute sync step
        var syncResult = context.step("sync-step", String.class, () -> "Sync Done");
        assertEquals("Sync Done", syncResult);
        
        // Execute async step
        var asyncFuture = context.stepAsync("async-step", Integer.class, () -> 42);
        assertEquals(42, asyncFuture.get());
        
        // Wait should suspend (throw exception)
        assertThrows(SuspendExecutionException.class, () -> {
            context.wait(Duration.ofSeconds(30));
        });
    }
    
    @Test
    void testCombinedReplay() throws Exception {
        // Create context with all operations completed
        var client = new LocalMemoryExecutionClient();
        var syncOp = Operation.builder()
                .id("1")
                .status(software.amazon.awssdk.services.lambda.model.OperationStatus.SUCCEEDED)
                .stepDetails(software.amazon.awssdk.services.lambda.model.StepDetails.builder()
                        .result("\"Replayed Sync\"")
                        .build())
                .build();
        
        var asyncOp = Operation.builder()
                .id("2")
                .status(software.amazon.awssdk.services.lambda.model.OperationStatus.SUCCEEDED)
                .stepDetails(software.amazon.awssdk.services.lambda.model.StepDetails.builder()
                        .result("100")
                        .build())
                .build();
        
        var waitOp = Operation.builder()
                .id("3")
                .status(software.amazon.awssdk.services.lambda.model.OperationStatus.SUCCEEDED)
                .build();
        
        var state = new ExecutionState("arn", "token", List.of(syncOp, asyncOp, waitOp));
        var executor = Executors.newSingleThreadExecutor();
        var checkpointManager = new CheckpointManager(state, client, executor);
        var coordinator = new ExecutionCoordinator(checkpointManager);
        var serDes = new JacksonSerDes();
        var context = new DurableContext(checkpointManager, serDes, null, coordinator);
        
        // All operations should replay from cache
        var syncResult = context.step("sync-step", String.class, () -> "New Sync");
        assertEquals("Replayed Sync", syncResult);
        
        var asyncFuture = context.stepAsync("async-step", Integer.class, () -> 999);
        assertTrue(asyncFuture.isDone());
        assertEquals(100, asyncFuture.get());
        
        // Wait should complete immediately (no exception)
        assertDoesNotThrow(() -> {
            context.wait(Duration.ofSeconds(30));
        });
    }
    
    @Test
    void testNamedWait() {
        var ctx = createTestContext();
        
        // Named wait should throw SuspendExecutionException
        assertThrows(SuspendExecutionException.class, () -> {
            ctx.wait("my-wait", Duration.ofSeconds(5));
        });
        
        // Verify it works without error (basic functionality test)
        assertDoesNotThrow(() -> {
            var ctx2 = createTestContext();
            try {
                ctx2.wait("another-wait", Duration.ofMinutes(1));
            } catch (SuspendExecutionException e) {
                // Expected - this means the method worked
            }
        });
    }
}
