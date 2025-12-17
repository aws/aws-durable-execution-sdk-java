package com.amazonaws.lambda.durable.checkpoint;

import com.amazonaws.lambda.durable.client.DurableExecutionClient;
import com.amazonaws.lambda.durable.execution.ExecutionCoordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationUpdate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates checkpoint batching, API calls, and state updates.
 * Consolidates all checkpoint-related logic in one place.
 */
public class CheckpointManager {
    private static final int MAX_BATCH_SIZE_BYTES = 750 * 1024; // 750KB
    private static final Logger logger = LoggerFactory.getLogger(CheckpointManager.class);
    private final ExecutionState state;
    private final DurableExecutionClient client;
    private final BlockingQueue<CheckpointRequest> queue = new LinkedBlockingQueue<>();
    private final ExecutorService executor;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private ExecutionCoordinator coordinator;
    
    record CheckpointRequest(OperationUpdate update, CompletableFuture<Void> completion) {}
    
    public CheckpointManager(ExecutionState state, DurableExecutionClient client, ExecutorService executor) {
        this.state = state;
        this.client = client;
        this.executor = executor;
    }
    
    public void setCoordinator(com.amazonaws.lambda.durable.execution.ExecutionCoordinator coordinator) {
        this.coordinator = coordinator;
    }
    
    public CompletableFuture<Void> checkpoint(OperationUpdate update) {
        logger.info("Checkpoint request received");
        var future = new CompletableFuture<Void>();
        queue.offer(new CheckpointRequest(update, future));
        
        if (isProcessing.compareAndSet(false, true)) {
            executor.submit(this::processQueue);
        }
        logger.info("Checkpoint request submitted");
        
        return future;
    }
    
    // Read methods for DurableContext
    public Optional<Operation> getOperation(String operationId) {
        return state.getOperation(operationId);
    }
    
    public void shutdown() {
        // Drain remaining items and fail them
        var remaining = new ArrayList<CheckpointRequest>();
        queue.drainTo(remaining);
        remaining.forEach(req -> 
            req.completion().completeExceptionally(new IllegalStateException("CheckpointManager shutdown")));
    }
    
    private void processQueue() {
        try {
            var batch = collectBatch();
            if (!batch.isEmpty()) {
                // Filter out null updates. A null update is sent when polling.
                // During polling, we are only interested in receiving operation updates from
                // the backend.
                var updates = batch.stream()
                    .map(CheckpointRequest::update)
                    .filter(u -> u != null)
                    .toList();

                logger.debug("--- Making API call ---");
                // Make API call (even with empty updates for polling)
                var response = client.checkpoint(
                    state.getDurableExecutionArn(),
                    state.getCheckpointToken(), 
                    updates
                );
                logger.debug("--- API call done ---");
                
                // Update state after success
                state.updateCheckpointToken(response.checkpointToken());
                state.updateOperations(response.newExecutionState().operations());
                
                // Advance phasers for completed operations (critical!)
                if (coordinator != null) {
                    coordinator.updateOperations(response.newExecutionState().operations());
                }

                logger.debug("--- After checkpoint ---");
                // Complete all futures
                batch.forEach(req -> req.completion().complete(null));
            }
        } catch (Exception e) {
            // Fail all futures in current batch
            var batch = new ArrayList<CheckpointRequest>();
            queue.drainTo(batch);
            batch.forEach(req -> req.completion().completeExceptionally(e));
        } finally {
            isProcessing.set(false);
            
            // Check if more items arrived while processing
            if (!queue.isEmpty() && isProcessing.compareAndSet(false, true)) {
                executor.submit(this::processQueue);
            }
        }
    }
    
    private List<CheckpointRequest> collectBatch() {
        var batch = new ArrayList<CheckpointRequest>();
        var currentSize = 0;
        
        CheckpointRequest req;
        while ((req = queue.poll()) != null) {
            var itemSize = estimateSize(req.update());
            
            // If adding this would exceed limit, and we have items, stop
            if (currentSize + itemSize > MAX_BATCH_SIZE_BYTES && !batch.isEmpty()) {
                // Put it back for next batch
                queue.offer(req);
                break;
            }
            
            batch.add(req);
            currentSize += itemSize;
        }
        
        return batch;
    }
    
    private int estimateSize(OperationUpdate update) {
        // Will be null when used for polling
        if (update == null) {
            return 0;
        }

        return update.id().length() + 
               update.type().toString().length() + 
               update.action().toString().length() + 
               (update.payload() != null ? update.payload().length() : 0) +
               100; // Overhead
    }
}
