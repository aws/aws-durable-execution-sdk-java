// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static software.amazon.lambda.durable.model.ConcurrencyCompletionStatus.CUSTOM_COMPLETION_SUCCEEDED;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import software.amazon.lambda.durable.ParallelDurableFuture;
import software.amazon.lambda.durable.config.CallbackConfig;
import software.amazon.lambda.durable.config.CompletionConfig;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.ParallelBranchConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.StepSemantics;
import software.amazon.lambda.durable.config.WaitForCallbackConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.model.WaitForConditionResult;
import software.amazon.lambda.durable.retry.RetryStrategy;
import software.amazon.lambda.durable.retry.WaitForConditionWaitStrategy;
import software.amazon.lambda.durable.serde.SerDes;

class DurableOperationConfigTest {
    @Test
    void operationApisOwnTheirConfigTypes() throws Exception {
        assertEquals(DurableConcurrencyOperation.class, DurableMapOperation.class.getSuperclass());
        assertEquals(DurableConcurrencyOperation.class, DurableParallelOperation.class.getSuperclass());
        assertPublicStaticNestedType(DurableConcurrencyOperation.class, "CompletionConfig");
        assertPublicStaticNestedType(DurableConcurrencyOperation.class, "NestingType");
        assertProtectedStaticNestedType(DurableConcurrencyOperation.class, "OperationConcurrencyCoordinator");
        assertProtectedStaticNestedType(DurableConcurrencyOperation.class, "DeferredDurableFuture");
        assertOperationConfig(DurableStepOperation.class, "StepConfig", StepConfig.class);
        assertOperationConfig(DurableInvokeOperation.class, "InvokeConfig", InvokeConfig.class);
        assertOperationConfig(DurableCallbackOperation.class, "CallbackConfig", CallbackConfig.class);
        assertOperationConfig(DurableContextOperation.class, "RunInChildContextConfig", RunInChildContextConfig.class);
        assertOperationConfig(DurableMapOperation.class, "MapConfig", MapConfig.class);
        assertOperationConfig(DurableParallelOperation.class, "ParallelConfig", ParallelConfig.class);
        assertOperationConfig(DurableParallelOperation.class, "ParallelBranchConfig", ParallelBranchConfig.class);
        assertParallelFutureUsesCompatibilityBranchConfig();
        assertOperationConfig(
                DurableWaitForCallbackOperation.class, "WaitForCallbackConfig", WaitForCallbackConfig.class);
        assertOperationConfig(
                DurableWaitForConditionOperation.class, "WaitForConditionConfig", WaitForConditionConfig.class);
        assertOperationOwnedType(
                DurableWaitForConditionOperation.class, "WaitForConditionResult", WaitForConditionResult.class);
        assertOperationConfig(DurableWithRetryOperation.class, "WithRetryConfig", WithRetryConfig.class);
    }

    @Test
    void legacyPrimitiveConfigsConvertWithoutLosingValues() throws Exception {
        var retryStrategy = mock(RetryStrategy.class);
        var serDes = mock(SerDes.class);
        var step = convert(StepConfig.builder()
                .retryStrategy(retryStrategy)
                .semanticsPerRetry(StepSemantics.AT_MOST_ONCE_PER_RETRY)
                .serDes(serDes)
                .build());
        assertSame(retryStrategy, value(step, "retryStrategy"));
        assertEquals(StepSemantics.AT_MOST_ONCE_PER_RETRY, value(step, "semanticsPerRetry"));
        assertSame(serDes, value(step, "serDes"));

        var payloadSerDes = mock(SerDes.class);
        var invoke = convert(InvokeConfig.builder()
                .payloadSerDes(payloadSerDes)
                .serDes(serDes)
                .tenantId("tenant")
                .build());
        assertSame(payloadSerDes, value(invoke, "payloadSerDes"));
        assertSame(serDes, value(invoke, "serDes"));
        assertEquals("tenant", value(invoke, "tenantId"));

        var callback = convert(CallbackConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .heartbeatTimeout(Duration.ofMinutes(1))
                .serDes(serDes)
                .build());
        assertEquals(Duration.ofMinutes(5), value(callback, "timeout"));
        assertEquals(Duration.ofMinutes(1), value(callback, "heartbeatTimeout"));
        assertSame(serDes, value(callback, "serDes"));

        var child = convert(
                RunInChildContextConfig.builder().serDes(serDes).isVirtual(true).build());
        assertSame(serDes, value(child, "serDes"));
        assertEquals(true, value(child, "isVirtual"));
    }

    @Test
    void legacyCompositeConfigsConvertWithoutLosingValues() throws Exception {
        var serDes = mock(SerDes.class);
        var completionConfig = CompletionConfig.firstSuccessful();
        BiFunction<Object, Integer, String> itemNamer = (item, index) -> item + "-" + index;
        var map = convert(MapConfig.builder()
                .maxConcurrency(3)
                .completionConfig(completionConfig)
                .serDes(serDes)
                .nestingType(NestingType.NESTED)
                .itemNamer(itemNamer)
                .build());
        assertEquals(3, value(map, "maxConcurrency"));
        assertEquals(completionConfig.toOperationConfig(), value(map, "completionConfig"));
        assertSame(serDes, value(map, "serDes"));
        assertEquals(DurableConcurrencyOperation.NestingType.NESTED, value(map, "nestingType"));
        assertSame(itemNamer, value(map, "itemNamer"));

        var parallel = convert(ParallelConfig.builder()
                .maxConcurrency(2)
                .completionConfig(completionConfig)
                .nestingType(NestingType.FLAT)
                .build());
        assertEquals(2, value(parallel, "maxConcurrency"));
        assertEquals(completionConfig.toOperationConfig(), value(parallel, "completionConfig"));
        assertEquals(DurableConcurrencyOperation.NestingType.FLAT, value(parallel, "nestingType"));

        var branch = convert(ParallelBranchConfig.builder().serDes(serDes).build());
        assertSame(serDes, value(branch, "serDes"));

        var retryStrategy = mock(RetryStrategy.class);
        var retry = convert(WithRetryConfig.builder()
                .retryStrategy(retryStrategy)
                .wrapInChildContext(true)
                .build());
        assertSame(retryStrategy, value(retry, "retryStrategy"));
        assertEquals(true, value(retry, "wrapInChildContext"));
    }

    @Test
    void legacyCustomCompletionConfigConvertsStatusAndDecision() {
        var legacy = CompletionConfig.shouldComplete(status -> status.successCount() == 2 && status.allItemsRegistered()
                ? CompletionConfig.CompletionDecision.complete(CUSTOM_COMPLETION_SUCCEEDED)
                : CompletionConfig.CompletionDecision.continueExecution());
        var operationConfig = legacy.toOperationConfig();

        var decision = operationConfig
                .completionDecisionFunction()
                .apply(new DurableConcurrencyOperation.CompletionConfig.CompletionStatus(2, 1, 3, 3, true));

        assertTrue(decision.shouldComplete());
        assertEquals(CUSTOM_COMPLETION_SUCCEEDED, decision.completionStatus());
    }

    @Test
    void legacyStatefulConfigsConvertWithoutLosingValues() throws Exception {
        var serDes = mock(SerDes.class);
        var stepConfig = StepConfig.builder().serDes(serDes).build();
        var callbackConfig =
                CallbackConfig.builder().timeout(Duration.ofMinutes(2)).build();
        var callback = convert(WaitForCallbackConfig.builder()
                .stepConfig(stepConfig)
                .callbackConfig(callbackConfig)
                .build());
        assertSame(serDes, value(value(callback, "stepConfig"), "serDes"));
        assertEquals(Duration.ofMinutes(2), value(value(callback, "callbackConfig"), "timeout"));

        @SuppressWarnings("unchecked")
        var waitStrategy = (WaitForConditionWaitStrategy<String>) mock(WaitForConditionWaitStrategy.class);
        var condition = convert(WaitForConditionConfig.<String>builder()
                .waitStrategy(waitStrategy)
                .serDes(serDes)
                .initialState("initial")
                .build());
        assertSame(waitStrategy, value(condition, "waitStrategy"));
        assertSame(serDes, value(condition, "serDes"));
        assertEquals("initial", value(condition, "initialState"));
    }

    private static void assertOperationConfig(Class<?> operationClass, String nestedName, Class<?> legacyClass)
            throws Exception {
        assertOperationConfig(operationClass, operationClass, nestedName, legacyClass);
    }

    private static void assertPublicStaticNestedType(Class<?> owner, String nestedName) throws Exception {
        var nestedClass = Class.forName(owner.getName() + "$" + nestedName);
        assertTrue(Modifier.isPublic(nestedClass.getModifiers()));
        assertTrue(Modifier.isStatic(nestedClass.getModifiers()));
    }

    private static void assertProtectedStaticNestedType(Class<?> owner, String nestedName) throws Exception {
        var nestedClass = Class.forName(owner.getName() + "$" + nestedName);
        assertTrue(Modifier.isProtected(nestedClass.getModifiers()));
        assertTrue(Modifier.isStatic(nestedClass.getModifiers()));
    }

    private static void assertOperationOwnedType(Class<?> operationClass, String nestedName, Class<?> legacyClass)
            throws Exception {
        assertPublicStaticNestedType(operationClass, nestedName);
        assertFalse(Arrays.stream(operationClass.getMethods())
                .map(Method::toGenericString)
                .anyMatch(signature -> signature.contains(legacyClass.getName())));
    }

    private static void assertParallelFutureUsesCompatibilityBranchConfig() {
        var nestedConfig = DurableParallelOperation.ParallelBranchConfig.class;
        assertTrue(Arrays.stream(ParallelDurableFuture.class.getMethods())
                .filter(method -> method.getName().equals("branch"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(ParallelBranchConfig.class::equals));
        assertFalse(Arrays.stream(ParallelDurableFuture.class.getMethods())
                .filter(method -> method.getName().equals("branch"))
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(nestedConfig::equals));
    }

    private static void assertOperationConfig(
            Class<?> apiClass, Class<?> operationClass, String nestedName, Class<?> legacyClass) throws Exception {
        var nestedClass = Class.forName(operationClass.getName() + "$" + nestedName);
        assertTrue(Modifier.isPublic(nestedClass.getModifiers()));
        assertTrue(Modifier.isStatic(nestedClass.getModifiers()));
        assertEquals(nestedClass, legacyClass.getMethod("toOperationConfig").getReturnType());
        assertFalse(Arrays.stream(apiClass.getMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes()))
                .anyMatch(legacyClass::equals));
    }

    private static Object convert(Object legacyConfig) throws Exception {
        return legacyConfig.getClass().getMethod("toOperationConfig").invoke(legacyConfig);
    }

    private static Object value(Object config, String methodName) throws Exception {
        return config.getClass().getMethod(methodName).invoke(config);
    }
}
