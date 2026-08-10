// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.context;

import com.amazonaws.services.lambda.runtime.Context;
import java.util.Collection;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import software.amazon.lambda.durable.DurableConfig;
import software.amazon.lambda.durable.DurableContext;
import software.amazon.lambda.durable.DurableFuture;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.StepContext;
import software.amazon.lambda.durable.TypeToken;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.execution.ExecutionManager;
import software.amazon.lambda.durable.execution.OperationIdGenerator;
import software.amazon.lambda.durable.execution.ThreadType;
import software.amazon.lambda.durable.extension.ExtensionContext;
import software.amazon.lambda.durable.extension.ExtensionOperation;
import software.amazon.lambda.durable.extension.ExtensionOperationImpl;
import software.amazon.lambda.durable.model.MapResult;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.operation.DurableMapOperation;
import software.amazon.lambda.durable.operation.DurableParallelOperation;
import software.amazon.lambda.durable.operation.DurableWaitForCallbackOperation;
import software.amazon.lambda.durable.operation.DurableWaitForConditionOperation;
import software.amazon.lambda.durable.operation.DurableWithRetryOperation;
import software.amazon.lambda.durable.primitive.BasePrimitive;
import software.amazon.lambda.durable.util.ParameterValidator;

/**
 * User-facing API for defining durable operations within a workflow.
 *
 * <p>Provides methods for creating steps, waits, chained invokes, callbacks, and child contexts. Each method creates a
 * checkpoint-backed operation that survives Lambda interruptions.
 */
public class DurableContextImpl extends BaseContextImpl implements DurableContext, ExtensionContext {
    private static final String WAIT_FOR_CALLBACK_CALLBACK_SUFFIX = "-callback";
    private static final String WAIT_FOR_CALLBACK_SUBMITTER_SUFFIX = "-submitter";
    private static final int MAX_WAIT_FOR_CALLBACK_NAME_LENGTH = ParameterValidator.MAX_OPERATION_NAME_LENGTH
            - Math.max(WAIT_FOR_CALLBACK_CALLBACK_SUFFIX.length(), WAIT_FOR_CALLBACK_SUBMITTER_SUFFIX.length());
    private final OperationIdGenerator operationIdGenerator;
    private final DurableContextImpl parentContext;
    private final BasePrimitive lateCheckpointOwner;
    private final boolean isVirtual;
    private boolean isReplaying;

    /** Shared initialization — sets all fields. */
    private DurableContextImpl(
            ExecutionManager executionManager,
            DurableConfig durableConfig,
            Context lambdaContext,
            String contextId,
            String contextName,
            boolean isVirtual,
            DurableContextImpl parentContext,
            BasePrimitive lateCheckpointOwner) {
        super(executionManager, durableConfig, lambdaContext, contextId, contextName, ThreadType.CONTEXT);
        operationIdGenerator = new OperationIdGenerator(contextId);
        this.parentContext = parentContext;
        this.lateCheckpointOwner = lateCheckpointOwner;
        this.isVirtual = isVirtual;
        this.isReplaying = executionManager.hasOperationsForContext(contextId);
    }

    /**
     * Creates a root context (contextId = null)
     *
     * <p>The context itself always has a null contextId (making it a root context).
     *
     * @param executionManager the execution manager
     * @param durableConfig the durable configuration
     * @param lambdaContext the Lambda context
     * @return a new root DurableContext
     */
    public static DurableContextImpl createRootContext(
            ExecutionManager executionManager, DurableConfig durableConfig, Context lambdaContext) {
        return new DurableContextImpl(executionManager, durableConfig, lambdaContext, null, null, false, null, null);
    }

    /**
     * Creates a child context.
     *
     * @param childContextId the child context's ID (the CONTEXT operation's operation ID)
     * @param childContextName the name of the child context
     * @param isVirtual whether the context is virtual
     * @return a new DurableContext for the child context
     */
    public DurableContextImpl createChildContext(String childContextId, String childContextName, boolean isVirtual) {
        return createChildContext(childContextId, childContextName, isVirtual, null);
    }

    public DurableContextImpl createChildContext(
            String childContextId, String childContextName, boolean isVirtual, BasePrimitive lateCheckpointOwner) {
        return new DurableContextImpl(
                getExecutionManager(),
                getDurableConfig(),
                getLambdaContext(),
                childContextId,
                childContextName,
                isVirtual,
                this,
                lateCheckpointOwner);
    }

    /**
     * Creates a step context for executing step operations.
     *
     * @param stepOperationId the ID of the step operation (used for thread registration)
     * @param stepOperationName the name of the step operation
     * @param attempt the current retry attempt number (1-based)
     * @return a new StepContext instance
     */
    public StepContextImpl createStepContext(String stepOperationId, String stepOperationName, int attempt) {
        return new StepContextImpl(
                getExecutionManager(),
                getDurableConfig(),
                getLambdaContext(),
                stepOperationId,
                stepOperationName,
                attempt);
    }

    BasePrimitive getLateCheckpointOwner() {
        return lateCheckpointOwner;
    }

    @Override
    public <I, O> DurableFuture<MapResult<O>> mapAsync(
            String name, Collection<I> items, TypeToken<O> resultType, MapFunction<I, O> function, MapConfig config) {
        return DurableMapOperation.mapAsync(this, name, items, resultType, function, config.toOperationConfig());
    }

    @Override
    public ParallelDurableFuture parallel(String name, ParallelConfig config) {
        return DurableParallelOperation.parallel(this, name, config.toOperationConfig());
    }

    @Override
    public <T> DurableFuture<T> waitForCallbackAsync(
            String name,
            TypeToken<T> resultType,
            BiConsumer<String, StepContext> func,
            WaitForCallbackConfig waitForCallbackConfig) {
        return DurableWaitForCallbackOperation.waitForCallbackAsync(
                this, name, resultType, func, waitForCallbackConfig.toOperationConfig());
    }

    @Override
    public <T> DurableFuture<T> waitForConditionAsync(
            String name,
            TypeToken<T> resultType,
            BiFunction<T, StepContext, WaitForConditionResult<T>> checkFunc,
            WaitForConditionConfig<T> config) {
        return DurableWaitForConditionOperation.waitForConditionAsync(
                this,
                name,
                resultType,
                (state, stepContext) -> {
                    var result = checkFunc.apply(state, stepContext);
                    return result == null
                            ? null
                            : new DurableWaitForConditionOperation.WaitForConditionResult<>(
                                    result.value(), result.isDone());
                },
                config.toOperationConfig());
    }

    // =============== withRetry ================

    @Override
    public <T> DurableFuture<T> withRetryAsync(
            String name, BiFunction<Integer, DurableContext, T> operation, WithRetryConfig config) {
        return DurableWithRetryOperation.withRetryAsync(this, name, operation, config.toOperationConfig());
    }

    // =============== accessors ================
    /**
     * Get the next operationId. Returns a globally unique operation ID by hashing a sequential operation counter. For
     * root contexts, the counter value is hashed directly (e.g. "1", "2", "3"). For child contexts, the values are
     * prefixed with the parent hashed contextId (e.g. "<hash>-1", "<hash>-2" inside parent context <hash>). This
     * matches the Python SDK's stepPrefix convention and prevents ID collisions in checkpoint batches.
     */
    private String nextOperationId() {
        return operationIdGenerator.nextOperationId();
    }

    private String nextOperationId(String localOperationId) {
        return operationIdGenerator.nextOperationId(localOperationId);
    }

    String reserveOperationId() {
        return nextOperationId();
    }

    String reserveOperationId(String localOperationId) {
        return nextOperationId(localOperationId);
    }

    @Override
    public ExtensionOperation reserve(String name) {
        ParameterValidator.validateOperationName(name);
        return new ExtensionOperationImpl(this, reserveOperationId(), name, lateCheckpointOwner);
    }

    @Override
    public ExtensionOperation reserve(String name, String localOperationId) {
        ParameterValidator.validateOperationName(name);
        return new ExtensionOperationImpl(this, reserveOperationId(localOperationId), name, lateCheckpointOwner);
    }

    /** Returns whether this context is currently in replay mode. */
    @Override
    public boolean isReplaying() {
        return isReplaying;
    }

    /**
     * Transitions this context from replay to execution mode. Called when the first un-cached operation is encountered.
     */
    public void setExecutionMode() {
        this.isReplaying = false;
    }

    /**
     * Get the parent context ID for its child operations, which always points to a non-virtual context
     *
     * @return the parent of this context if virtual, otherwise this context id
     */
    public String getParentId() {
        return isVirtual ? parentContext.getParentId() : getContextId();
    }
}
