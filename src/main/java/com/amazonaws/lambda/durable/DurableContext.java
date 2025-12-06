package com.amazonaws.lambda.durable;

import com.amazonaws.lambda.durable.model.Operation;
import com.amazonaws.lambda.durable.serde.JacksonSerDes;
import com.amazonaws.lambda.durable.serde.SerDes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Main API for durable execution.
 * Provides step execution with checkpointing and replay.
 */
public class DurableContext {
    
    private int operationCounter = 0;
    private final Map<Integer, Operation> checkpointLog;
    private final SerDes serDes;
    
    /**
     * Create context with empty checkpoint log (for testing or first execution).
     */
    public DurableContext() {
        this(new HashMap<>(), new JacksonSerDes());
    }
    
    /**
     * Create context with existing checkpoint log (for replay).
     */
    public DurableContext(Map<Integer, Operation> checkpointLog) {
        this(checkpointLog, new JacksonSerDes());
    }
    
    /**
     * Create context with checkpoint log and custom SerDes.
     */
    public DurableContext(Map<Integer, Operation> checkpointLog, SerDes serDes) {
        this.checkpointLog = new HashMap<>(checkpointLog);
        this.serDes = serDes;
    }
    
    /**
     * Execute a step with checkpointing.
     * Blocks until complete.
     */
    public <T> T step(String name, Class<T> type, Callable<T> action) {
        var opId = operationCounter++;
        
        // Check if already completed (REPLAY)
        var existing = checkpointLog.get(opId);
        if (existing != null) {
            System.out.println("REPLAY: Skipping step: " + name + " (id=" + opId + ")");
            return serDes.deserialize(existing.getResult(), type);
        }
        
        // Not in log - execute normally
        try {
            System.out.println("Executing step: " + name + " (id=" + opId + ")");
            var result = action.call();
            
            // Store in checkpoint log with proper serialization
            var resultJson = serDes.serialize(result);
            checkpointLog.put(opId, new Operation(String.valueOf(opId), name, resultJson));
            
            System.out.println("Step completed: " + name + " -> " + result);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Step failed: " + name, e);
        }
    }
    
    /**
     * Get checkpoint log for inspection (useful for testing).
     */
    public Map<Integer, Operation> getCheckpointLog() {
        return checkpointLog;
    }
}
