package com.amazonaws.lambda.durable.testing;

import com.amazonaws.lambda.durable.serde.SerDes;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.awssdk.services.lambda.model.StepDetails;
import software.amazon.awssdk.services.lambda.model.WaitDetails;

/**
 * Wrapper for AWS SDK Operation providing convenient access methods.
 */
public class TestOperation {
    private final Operation operation;
    private final SerDes serDes;
    
    TestOperation(Operation operation, SerDes serDes) {
        this.operation = operation;
        this.serDes = serDes;
    }
    
    public String getName() {
        return operation.name();
    }
    
    public OperationStatus getStatus() {
        return operation.status();
    }
    
    public OperationType getType() {
        return operation.type();
    }
    
    public StepDetails getStepDetails() {
        return operation.stepDetails();
    }
    
    public WaitDetails getWaitDetails() {
        return operation.waitDetails();
    }
    
    /**
     * Type-safe result extraction from step details.
     */
    public <T> T getStepResult(Class<T> type) {
        var details = operation.stepDetails();
        if (details == null || details.result() == null) {
            return null;
        }
        return serDes.deserialize(details.result(), type);
    }
    
    public ErrorObject getError() {
        var details = operation.stepDetails();
        return details != null ? details.error() : null;
    }
    
    public int getAttempt() {
        var details = operation.stepDetails();
        return details != null && details.attempt() != null ? details.attempt() : 1;
    }
}
