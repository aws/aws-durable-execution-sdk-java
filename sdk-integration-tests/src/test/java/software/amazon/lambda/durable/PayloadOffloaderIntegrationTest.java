// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.lambda.model.CheckpointUpdatedExecutionState;
import software.amazon.awssdk.services.lambda.model.ErrorObject;
import software.amazon.awssdk.services.lambda.model.ExecutionDetails;
import software.amazon.awssdk.services.lambda.model.Operation;
import software.amazon.awssdk.services.lambda.model.OperationAction;
import software.amazon.awssdk.services.lambda.model.OperationStatus;
import software.amazon.awssdk.services.lambda.model.OperationType;
import software.amazon.lambda.durable.config.InvokeConfig;
import software.amazon.lambda.durable.config.MapConfig;
import software.amazon.lambda.durable.config.NestingType;
import software.amazon.lambda.durable.config.ParallelBranchConfig;
import software.amazon.lambda.durable.config.ParallelConfig;
import software.amazon.lambda.durable.config.RunInChildContextConfig;
import software.amazon.lambda.durable.config.StepConfig;
import software.amazon.lambda.durable.config.WaitForConditionConfig;
import software.amazon.lambda.durable.config.WithRetryConfig;
import software.amazon.lambda.durable.exception.CallbackFailedException;
import software.amazon.lambda.durable.exception.DurableOperationException;
import software.amazon.lambda.durable.exception.InvokeFailedException;
import software.amazon.lambda.durable.exception.PayloadOffloadException;
import software.amazon.lambda.durable.exception.RetryablePayloadOffloadException;
import software.amazon.lambda.durable.execution.DurableExecutor;
import software.amazon.lambda.durable.execution.PayloadCodec;
import software.amazon.lambda.durable.execution.SuspendExecutionException;
import software.amazon.lambda.durable.model.DurableExecutionInput;
import software.amazon.lambda.durable.model.ExecutionStatus;
import software.amazon.lambda.durable.model.InvocationSource;
import software.amazon.lambda.durable.model.OperationIdentifier;
import software.amazon.lambda.durable.model.OperationSubType;
import software.amazon.lambda.durable.offload.OffloadedPayload;
import software.amazon.lambda.durable.offload.PayloadOffloadContext;
import software.amazon.lambda.durable.offload.PayloadOffloader;
import software.amazon.lambda.durable.offload.SerDesPayloadKind;
import software.amazon.lambda.durable.offload.filesystem.FileSystemPayloadOffloader;
import software.amazon.lambda.durable.offload.filesystem.PreviewConfig;
import software.amazon.lambda.durable.offload.filesystem.PreviewMode;
import software.amazon.lambda.durable.offload.internal.ChainedInvokePayloadFrame;
import software.amazon.lambda.durable.retry.RetryDecision;
import software.amazon.lambda.durable.retry.RetryStrategies;
import software.amazon.lambda.durable.serde.JacksonSerDes;
import software.amazon.lambda.durable.serde.SerDes;
import software.amazon.lambda.durable.testing.LocalDurableTestRunner;
import software.amazon.lambda.durable.testing.local.LocalMemoryExecutionClient;
import software.amazon.lambda.durable.testing.local.OperationResult;

class PayloadOffloaderIntegrationTest {
    @TempDir
    Path payloadDirectory;

    @Test
    void filesystemOffloaderReplaysStepAndRootOutput() throws IOException {
        var stepExecutions = new AtomicInteger();
        var config = DurableConfig.builder()
                .withPayloadOffloader(
                        FileSystemPayloadOffloader.builder(payloadDirectory).build())
                .build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var future = context.stepAsync("offloaded-step", String.class, stepContext -> {
                        stepExecutions.incrementAndGet();
                        return "stored-" + input;
                    });
                    var first = future.get();
                    var second = future.get();
                    context.wait("replay-boundary", Duration.ofSeconds(1));
                    return first + ":" + second;
                },
                config);

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("stored-value:stored-value", result.getResult(String.class));
        assertEquals("stored-value", result.getOperation("offloaded-step").getStepResult(String.class));
        assertEquals(1, stepExecutions.get());
        try (var files = Files.walk(payloadDirectory)) {
            assertTrue(files.filter(Files::isRegularFile).count() >= 2);
        }
    }

    @Test
    void offloadedExceptionIsReconstructedAfterReplay() {
        var config = DurableConfig.builder()
                .withPayloadOffloader(
                        FileSystemPayloadOffloader.builder(payloadDirectory).build())
                .build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    try {
                        context.step(
                                "failing-step",
                                String.class,
                                stepContext -> {
                                    throw new IllegalStateException("offloaded failure");
                                },
                                StepConfig.builder()
                                        .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                        .build());
                        return "unreachable";
                    } catch (IllegalStateException expected) {
                        context.wait("replay-after-failure", Duration.ofSeconds(1));
                        return expected.getMessage();
                    }
                },
                config);

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("offloaded failure", result.getResult(String.class));
    }

    @Test
    void operationCanDisableGlobalOffloader() {
        var config = DurableConfig.builder()
                .withPayloadOffloader(
                        FileSystemPayloadOffloader.builder(payloadDirectory).build())
                .build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.step(
                        "inline-step",
                        String.class,
                        stepContext -> "inline",
                        StepConfig.builder()
                                .payloadOffloader(PayloadOffloader.disabled())
                                .build()),
                config);

        var result = runner.run("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(
                "\"inline\"",
                result.getOperation("inline-step").getStepDetails().result());
    }

    @Test
    void reservedPayloadMarkerRoundTripsThroughFirstExecutionAndReplayWithoutOffloader() {
        var marker = "@aws-durable-payload:v2:{}";
        var stepExecutions = new AtomicInteger();
        SerDes passThroughSerDes = new SerDes() {
            @Override
            public String serialize(Object value) {
                return (String) value;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                return (T) data;
            }
        };
        var config = DurableConfig.builder().withSerDes(passThroughSerDes).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    var result = context.step("marker-step", String.class, stepContext -> {
                        stepExecutions.incrementAndGet();
                        return marker;
                    });
                    context.wait("replay-boundary", Duration.ofSeconds(1));
                    return result;
                },
                config);

        var result = runner.runUntilComplete("input");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(marker, result.getResult(String.class));
        assertTrue(result.getOperation("marker-step").getStepDetails().result().startsWith("@aws-durable-payload:v1:"));
        assertEquals(1, stepExecutions.get());
    }

    @Test
    void nullStepAndRootOutputReplayWithoutOffloading() {
        var offloader = new CountingPayloadOffloader();
        var stepExecutions = new AtomicInteger();
        var config = DurableConfig.builder().withPayloadOffloader(offloader).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    assertNull(context.step("null-step", String.class, stepContext -> {
                        stepExecutions.incrementAndGet();
                        return null;
                    }));
                    context.wait("replay-boundary", Duration.ofSeconds(1));
                    return null;
                },
                config);

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertNull(result.getResult(String.class));
        assertEquals(1, stepExecutions.get());
        assertEquals(0, offloader.offloadCount());
    }

    @Test
    void flatMapOffloadsOnlyCheckpointedAggregateResult() {
        var offloader = new CountingPayloadOffloader();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.map(
                    "flat-map",
                    List.of("a", "b"),
                    String.class,
                    (item, index, childContext) -> item.toUpperCase(),
                    MapConfig.builder()
                            .nestingType(NestingType.FLAT)
                            .payloadOffloader(offloader)
                            .build());
            return String.join(",", result.results());
        });

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("A,B", result.getResult(String.class));
        assertEquals(1, offloader.offloadCount());
    }

    @Test
    void flatMapPreservesMarkerPrefixedStandardInvokeFailure() {
        var marker = "@aws-durable-payload:v2:{}";
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var mapResult = context.map(
                    "flat-marker-map",
                    List.of("item"),
                    String.class,
                    (item, index, child) -> child.invoke("flat-map-standard-invoke", "standard", "{}", String.class),
                    MapConfig.builder().nestingType(NestingType.FLAT).build());
            return mapResult.failed().size() + ":" + mapResult.succeeded().size();
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("value").getStatus());
        runner.failChainedInvoke(
                "flat-map-standard-invoke",
                ErrorObject.builder()
                        .errorType("RemoteError")
                        .errorMessage("remote failure")
                        .errorData(marker)
                        .build());

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("1:0", result.getResult(String.class));
    }

    @Test
    void flatParallelPreservesMarkerPrefixedStandardInvokeFailure() {
        var marker = "@aws-durable-payload:v2:{}";
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var parallel = context.parallel(
                    "flat-marker-parallel",
                    ParallelConfig.builder().nestingType(NestingType.FLAT).build());
            try (parallel) {
                parallel.branch(
                        "branch",
                        String.class,
                        child -> child.invoke("flat-parallel-standard-invoke", "standard", "{}", String.class));
            }
            var parallelResult = parallel.get();
            return parallelResult.failed() + ":" + parallelResult.succeeded();
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("value").getStatus());
        runner.failChainedInvoke(
                "flat-parallel-standard-invoke",
                ErrorObject.builder()
                        .errorType("RemoteError")
                        .errorMessage("remote failure")
                        .errorData(marker)
                        .build());

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("1:0", result.getResult(String.class));
    }

    @Test
    void flatMapEscapesMarkerPrefixedCustomExceptionSerialization() {
        var serDes = markerExceptionSerDes();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var mapResult = context.map(
                    "flat-custom-error-map",
                    List.of("item"),
                    String.class,
                    (item, index, child) -> {
                        throw new IllegalStateException("branch failure");
                    },
                    MapConfig.builder()
                            .serDes(serDes)
                            .nestingType(NestingType.FLAT)
                            .build());
            return mapResult.failed().size();
        });

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, result.getResult(Integer.class));
    }

    @Test
    void flatParallelEscapesMarkerPrefixedCustomExceptionSerialization() {
        var serDes = markerExceptionSerDes();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var parallel = context.parallel(
                    "flat-custom-error-parallel",
                    ParallelConfig.builder().nestingType(NestingType.FLAT).build());
            try (parallel) {
                parallel.branch(
                        "branch",
                        String.class,
                        child -> {
                            throw new IllegalStateException("branch failure");
                        },
                        ParallelBranchConfig.builder().serDes(serDes).build());
            }
            return parallel.get().failed();
        });

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals(1, result.getResult(Integer.class));
    }

    @Test
    void completedParallelReplayDoesNotOffloadAgain() {
        var offloader = new CountingPayloadOffloader();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var parallel = context.parallel(
                    "parallel",
                    ParallelConfig.builder().payloadOffloader(offloader).build());
            try (parallel) {
                parallel.branch("first", String.class, childContext -> "one");
                parallel.branch("second", String.class, childContext -> "two");
            }
            return parallel.get().succeeded();
        });

        var first = runner.runUntilComplete("value");
        assertEquals(ExecutionStatus.SUCCEEDED, first.getStatus());
        assertEquals(1, offloader.offloadCount());

        var replay = runner.run("value");

        assertEquals(ExecutionStatus.SUCCEEDED, replay.getStatus());
        assertEquals(1, offloader.offloadCount());
    }

    @Test
    void mapReplayPayloadLoadFailureEscapesBusinessOutcomeHandling() {
        var failIterationLoads = new AtomicBoolean();
        var offloader = replayFailingOffloader(
                context -> context.operationSubType()
                        == software.amazon.lambda.durable.model.OperationSubType.MAP_ITERATION,
                failIterationLoads);
        var largeResult = "x".repeat(300 * 1024);
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> context.map(
                        "map",
                        List.of("a"),
                        String.class,
                        (item, index, childContext) -> largeResult,
                        MapConfig.builder().payloadOffloader(offloader).build())
                .results()
                .get(0));

        assertEquals(ExecutionStatus.SUCCEEDED, runner.runUntilComplete("value").getStatus());
        failIterationLoads.set(true);

        assertThrows(RetryablePayloadOffloadException.class, () -> runner.run("value"));
    }

    @Test
    void parallelReplayPayloadLoadFailureEscapesBusinessOutcomeHandling() {
        var failBranchLoads = new AtomicBoolean();
        var branchOffloader = replayFailingOffloader(
                context -> context.operationSubType()
                        == software.amazon.lambda.durable.model.OperationSubType.PARALLEL_BRANCH,
                failBranchLoads);
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var parallel = context.parallel("parallel");
            try (parallel) {
                parallel.branch(
                        "branch",
                        String.class,
                        childContext -> "result",
                        ParallelBranchConfig.builder()
                                .payloadOffloader(branchOffloader)
                                .build());
            }
            return parallel.get().succeeded();
        });

        assertEquals(ExecutionStatus.SUCCEEDED, runner.runUntilComplete("value").getStatus());
        failBranchLoads.set(true);

        assertThrows(RetryablePayloadOffloadException.class, () -> runner.run("value"));
    }

    @Test
    void callerAndCalleeExchangeOffloadedInvokePayloadAndResult() {
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var calleeArn =
                "arn:aws:lambda:us-east-1:123456789012:function:callee:1/durable-execution/callee-execution/callee-invocation";
        var offloader = new ContextKeyedPayloadOffloader();
        var callerClient = new LocalMemoryExecutionClient();
        var callerConfig = DurableConfig.builder()
                .withDurableExecutionClient(callerClient)
                .withPayloadOffloader(offloader)
                .build();
        BiFunction<String, DurableContext, CrossInvokeResponse> callerHandler = (input, context) -> context.invoke(
                "call-callee",
                "callee",
                new CrossInvokeRequest(input),
                CrossInvokeResponse.class,
                InvokeConfig.builder().usePayloadOffloaderForPayload(true).build());
        var callerExecution = executionOperation("caller-invocation", "caller-execution", "\"request\"");

        var pending = DurableExecutor.execute(
                durableInput(callerArn, callerExecution, List.of(), List.of()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);

        assertEquals(ExecutionStatus.PENDING, pending.status());
        var invokePayload = callerClient.getOperationUpdates().stream()
                .filter(update ->
                        update.type() == OperationType.CHAINED_INVOKE && update.action() == OperationAction.START)
                .findFirst()
                .orElseThrow()
                .payload();
        assertTrue(ChainedInvokePayloadFrame.isFramed(invokePayload));

        var calleeClient = new LocalMemoryExecutionClient();
        var calleeConfig = DurableConfig.builder()
                .withDurableExecutionClient(calleeClient)
                .withPayloadOffloader(offloader)
                .withPayloadOffloaderForChainedInvokePayloads(true)
                .build();
        var calleeExecution = executionOperation("callee-invocation", "callee-execution", invokePayload);
        var calleeOutput = DurableExecutor.execute(
                durableInput(calleeArn, calleeExecution, List.of(), List.of(), InvocationSource.CHAINED_INVOKE),
                null,
                TypeToken.get(CrossInvokeRequest.class),
                (request, context) -> new CrossInvokeResponse("reply:" + request.value()),
                calleeConfig);

        assertEquals(ExecutionStatus.SUCCEEDED, calleeOutput.status());
        callerClient.completeChainedInvoke("call-callee", OperationResult.succeeded(calleeOutput.result()));
        var resumed = DurableExecutor.execute(
                durableInput(
                        callerArn,
                        callerExecution,
                        callerClient.getAllOperations(),
                        callerClient.getUpdatedOperationIdsSinceLastInvocation()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);
        var result = new PayloadCodec(null)
                .deserialize(
                        resumed.result(),
                        TypeToken.get(CrossInvokeResponse.class),
                        new JacksonSerDes(),
                        offloader,
                        PayloadOffloadContext.forExecution(
                                callerArn, "caller-invocation", "caller-execution", SerDesPayloadKind.OUTPUT));

        assertEquals(ExecutionStatus.SUCCEEDED, resumed.status());
        assertEquals(new CrossInvokeResponse("reply:request"), result);
    }

    @Test
    void nullInvokeRequestRetainsHandshakeForOffloadedResult() {
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var calleeArn =
                "arn:aws:lambda:us-east-1:123456789012:function:callee:1/durable-execution/callee-execution/callee-invocation";
        var offloader = new ContextKeyedPayloadOffloader();
        var callerClient = new LocalMemoryExecutionClient();
        var callerConfig = DurableConfig.builder()
                .withDurableExecutionClient(callerClient)
                .withPayloadOffloader(offloader)
                .build();
        BiFunction<String, DurableContext, String> callerHandler = (input, context) -> context.invoke(
                "call-null-callee",
                "callee",
                (String) null,
                String.class,
                InvokeConfig.builder().usePayloadOffloaderForPayload(true).build());
        var callerExecution = executionOperation("caller-invocation", "caller-execution", "\"request\"");

        var pending = DurableExecutor.execute(
                durableInput(callerArn, callerExecution, List.of(), List.of()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);
        var invokePayload = callerClient.getOperationUpdates().stream()
                .filter(update ->
                        update.type() == OperationType.CHAINED_INVOKE && update.action() == OperationAction.START)
                .findFirst()
                .orElseThrow()
                .payload();

        assertEquals(ExecutionStatus.PENDING, pending.status());
        assertTrue(ChainedInvokePayloadFrame.isFramed(invokePayload));
        assertNull(ChainedInvokePayloadFrame.decode(invokePayload));

        var calleeConfig = DurableConfig.builder()
                .withDurableExecutionClient(new LocalMemoryExecutionClient())
                .withPayloadOffloader(offloader)
                .withPayloadOffloaderForChainedInvokePayloads(true)
                .build();
        var calleeOutput = DurableExecutor.execute(
                durableInput(
                        calleeArn,
                        executionOperation("callee-invocation", "callee-execution", invokePayload),
                        List.of(),
                        List.of(),
                        InvocationSource.CHAINED_INVOKE),
                null,
                TypeToken.get(String.class),
                (request, context) -> {
                    assertNull(request);
                    return "reply";
                },
                calleeConfig);

        callerClient.completeChainedInvoke("call-null-callee", OperationResult.succeeded(calleeOutput.result()));
        var resumed = DurableExecutor.execute(
                durableInput(
                        callerArn,
                        callerExecution,
                        callerClient.getAllOperations(),
                        callerClient.getUpdatedOperationIdsSinceLastInvocation()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);
        var result = new PayloadCodec(null)
                .deserialize(
                        resumed.result(),
                        TypeToken.get(String.class),
                        new JacksonSerDes(),
                        offloader,
                        PayloadOffloadContext.forExecution(
                                callerArn, "caller-invocation", "caller-execution", SerDesPayloadKind.OUTPUT));

        assertEquals(ExecutionStatus.SUCCEEDED, calleeOutput.status());
        assertEquals(ExecutionStatus.SUCCEEDED, resumed.status());
        assertEquals("reply", result);
    }

    @Test
    void defaultCallerReceivesOrdinaryResultFromOffloadingTarget() {
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var calleeArn =
                "arn:aws:lambda:us-east-1:123456789012:function:callee:1/durable-execution/callee-execution/callee-invocation";
        var callerClient = new LocalMemoryExecutionClient();
        var callerConfig =
                DurableConfig.builder().withDurableExecutionClient(callerClient).build();
        BiFunction<String, DurableContext, String> callerHandler =
                (input, context) -> context.invoke("call-callee", "callee", input, String.class);
        var callerExecution = executionOperation("caller-invocation", "caller-execution", "\"request\"");

        var pending = DurableExecutor.execute(
                durableInput(callerArn, callerExecution, List.of(), List.of()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);
        var invokePayload = callerClient.getOperationUpdates().stream()
                .filter(update ->
                        update.type() == OperationType.CHAINED_INVOKE && update.action() == OperationAction.START)
                .findFirst()
                .orElseThrow()
                .payload();
        var targetOffloader = new CountingPayloadOffloader();
        var targetConfig = DurableConfig.builder()
                .withDurableExecutionClient(new LocalMemoryExecutionClient())
                .withPayloadOffloader(targetOffloader)
                .build();
        var targetOutput = DurableExecutor.execute(
                durableInput(
                        calleeArn,
                        executionOperation("callee-invocation", "callee-execution", invokePayload),
                        List.of(),
                        List.of(),
                        InvocationSource.CHAINED_INVOKE),
                null,
                TypeToken.get(String.class),
                (request, context) -> "reply:" + request,
                targetConfig);

        assertEquals(ExecutionStatus.PENDING, pending.status());
        assertTrue(!ChainedInvokePayloadFrame.isFramed(invokePayload));
        assertEquals(ExecutionStatus.SUCCEEDED, targetOutput.status());
        assertTrue(!PayloadCodec.isOffloadEnvelope(targetOutput.result()));
        assertEquals(0, targetOffloader.offloadCount());

        callerClient.completeChainedInvoke("call-callee", OperationResult.succeeded(targetOutput.result()));
        var resumed = DurableExecutor.execute(
                durableInput(
                        callerArn,
                        callerExecution,
                        callerClient.getAllOperations(),
                        callerClient.getUpdatedOperationIdsSinceLastInvocation()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);

        assertEquals(ExecutionStatus.SUCCEEDED, resumed.status());
        assertEquals("\"reply:request\"", resumed.result());
    }

    @Test
    void defaultCallerReceivesOrdinaryErrorFromOffloadingTarget() {
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var calleeArn =
                "arn:aws:lambda:us-east-1:123456789012:function:callee:1/durable-execution/callee-execution/callee-invocation";
        var callerClient = new LocalMemoryExecutionClient();
        var callerConfig =
                DurableConfig.builder().withDurableExecutionClient(callerClient).build();
        BiFunction<String, DurableContext, String> callerHandler = (input, context) -> {
            try {
                return context.invoke("call-callee", "callee", input, String.class);
            } catch (InvokeFailedException expected) {
                return expected.getErrorObject().errorMessage();
            }
        };
        var callerExecution = executionOperation("caller-invocation", "caller-execution", "\"request\"");

        var pending = DurableExecutor.execute(
                durableInput(callerArn, callerExecution, List.of(), List.of()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);
        var invokePayload = callerClient.getOperationUpdates().stream()
                .filter(update ->
                        update.type() == OperationType.CHAINED_INVOKE && update.action() == OperationAction.START)
                .findFirst()
                .orElseThrow()
                .payload();
        var targetOffloader = new CountingPayloadOffloader();
        var targetConfig = DurableConfig.builder()
                .withDurableExecutionClient(new LocalMemoryExecutionClient())
                .withPayloadOffloader(targetOffloader)
                .build();
        BiFunction<String, DurableContext, String> targetHandler = (request, context) -> {
            throw new IllegalStateException("target failed");
        };
        var targetOutput = DurableExecutor.execute(
                durableInput(
                        calleeArn,
                        executionOperation("callee-invocation", "callee-execution", invokePayload),
                        List.of(),
                        List.of(),
                        InvocationSource.CHAINED_INVOKE),
                null,
                TypeToken.get(String.class),
                targetHandler,
                targetConfig);

        assertEquals(ExecutionStatus.PENDING, pending.status());
        assertEquals(ExecutionStatus.FAILED, targetOutput.status());
        assertTrue(!PayloadCodec.isOffloadEnvelope(targetOutput.error().errorData()));
        assertEquals(0, targetOffloader.offloadCount());

        callerClient.completeChainedInvoke("call-callee", OperationResult.failed(targetOutput.error()));
        var resumed = DurableExecutor.execute(
                durableInput(
                        callerArn,
                        callerExecution,
                        callerClient.getAllOperations(),
                        callerClient.getUpdatedOperationIdsSinceLastInvocation()),
                null,
                TypeToken.get(String.class),
                callerHandler,
                callerConfig);

        assertEquals(ExecutionStatus.SUCCEEDED, resumed.status());
        assertEquals("\"target failed\"", resumed.result());
    }

    @Test
    void stepRebindsSourceOwnedFailureBeforeReplay() {
        var sourceOffloader = new ContextKeyedPayloadOffloader();
        var targetOffloader = new ContextKeyedPayloadOffloader();
        var forwardedFailure = sourceBackedFailure(sourceOffloader, "source-step");
        var executions = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            try {
                context.step(
                        "forwarding-step",
                        String.class,
                        stepContext -> {
                            executions.incrementAndGet();
                            throw forwardedFailure;
                        },
                        StepConfig.builder()
                                .payloadOffloader(targetOffloader)
                                .retryStrategy(RetryStrategies.Presets.NO_RETRY)
                                .build());
                return "unreachable";
            } catch (IllegalStateException expected) {
                context.wait("step-replay-boundary", Duration.ofSeconds(1));
                return expected.getMessage();
            }
        });

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("forwarded failure", result.getResult(String.class));
        assertEquals(1, executions.get());
    }

    @Test
    void waitForConditionRebindsSourceOwnedFailureBeforeReplay() {
        var sourceOffloader = new ContextKeyedPayloadOffloader();
        var targetOffloader = new ContextKeyedPayloadOffloader();
        var forwardedFailure = sourceBackedFailure(sourceOffloader, "source-condition");
        var executions = new AtomicInteger();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            try {
                context.waitForCondition(
                        "forwarding-condition",
                        String.class,
                        (state, stepContext) -> {
                            executions.incrementAndGet();
                            throw forwardedFailure;
                        },
                        WaitForConditionConfig.<String>builder()
                                .initialState("state")
                                .payloadOffloader(targetOffloader)
                                .build());
                return "unreachable";
            } catch (IllegalStateException expected) {
                context.wait("condition-replay-boundary", Duration.ofSeconds(1));
                return expected.getMessage();
            }
        });

        var result = runner.runUntilComplete("value");

        assertEquals(ExecutionStatus.SUCCEEDED, result.getStatus());
        assertEquals("forwarded failure", result.getResult(String.class));
        assertEquals(1, executions.get());
    }

    @Test
    void retryableOffloadFailureEscapesStepOutcomeHandling() {
        var userExecutions = new AtomicInteger();
        var offloadAttempts = new AtomicInteger();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                offloadAttempts.incrementAndGet();
                throw new RetryablePayloadOffloadException("storage unavailable");
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                throw new AssertionError("load should not be called");
            }
        };
        var client = new LocalMemoryExecutionClient();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(client)
                .withPayloadOffloader(offloader)
                .build();
        var executionArn =
                "arn:aws:lambda:us-east-1:123456789012:function:test:1/durable-execution/execution/invocation";
        var execution = executionOperation("invocation", "execution", "\"input\"");

        assertThrows(
                RetryablePayloadOffloadException.class,
                () -> DurableExecutor.execute(
                        durableInput(executionArn, execution, List.of(), List.of()),
                        null,
                        TypeToken.get(String.class),
                        (input, context) -> context.step("successful-user-code", String.class, stepContext -> {
                            userExecutions.incrementAndGet();
                            return "result";
                        }),
                        config));

        assertEquals(1, userExecutions.get());
        assertEquals(1, offloadAttempts.get());
        assertTrue(client.getOperationUpdates().stream()
                .noneMatch(
                        update -> update.action() == OperationAction.FAIL || update.action() == OperationAction.RETRY));
    }

    @Test
    void withRetryDoesNotHandleFirstExecutionPayloadOffloadFailure() {
        var userExecutions = new AtomicInteger();
        var retryDecisions = new AtomicInteger();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                throw new RetryablePayloadOffloadException("storage unavailable");
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                throw new AssertionError("load should not be called");
            }
        };
        var retryConfig = WithRetryConfig.builder()
                .retryStrategy((error, attempt) -> {
                    retryDecisions.incrementAndGet();
                    return RetryDecision.retry(Duration.ofSeconds(1));
                })
                .build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.withRetry(
                        "retry",
                        (attempt, retryContext) -> retryContext.step(
                                "step",
                                String.class,
                                stepContext -> {
                                    userExecutions.incrementAndGet();
                                    return "result";
                                },
                                StepConfig.builder().payloadOffloader(offloader).build()),
                        retryConfig));

        assertThrows(RetryablePayloadOffloadException.class, () -> runner.run("value"));

        assertEquals(1, userExecutions.get());
        assertEquals(0, retryDecisions.get());
    }

    @Test
    void withRetryDoesNotHandleReplayPayloadLoadFailure() {
        var userExecutions = new AtomicInteger();
        var retryDecisions = new AtomicInteger();
        var failLoads = new AtomicBoolean();
        var values = new ConcurrentHashMap<String, String>();
        var sequence = new AtomicInteger();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                var reference = "memory://" + sequence.incrementAndGet();
                values.put(reference, serializedPayload);
                return OffloadedPayload.reference(reference, null);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                if (failLoads.get()) {
                    throw new RetryablePayloadOffloadException("storage unavailable during replay");
                }
                return values.get(payload.reference());
            }
        };
        var retryConfig = WithRetryConfig.builder()
                .retryStrategy((error, attempt) -> {
                    retryDecisions.incrementAndGet();
                    return RetryDecision.retry(Duration.ofSeconds(1));
                })
                .build();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            var result = context.withRetry(
                    "retry",
                    (attempt, retryContext) -> retryContext.step(
                            "step",
                            String.class,
                            stepContext -> {
                                userExecutions.incrementAndGet();
                                return "result";
                            },
                            StepConfig.builder().payloadOffloader(offloader).build()),
                    retryConfig);
            context.wait("replay-boundary", Duration.ofSeconds(1));
            return result;
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("value").getStatus());
        assertEquals(1, userExecutions.get());
        failLoads.set(true);
        runner.advanceTime();

        assertThrows(RetryablePayloadOffloadException.class, () -> runner.run("value"));

        assertEquals(1, userExecutions.get());
        assertEquals(0, retryDecisions.get());
    }

    @Test
    void waitForCallbackUnsupportedEnvelopeRemainsExternalFailure() {
        var unsupportedEnvelope = "@aws-durable-payload:v2:{}";
        var observedErrorData = new AtomicReference<String>();
        var runner = LocalDurableTestRunner.create(String.class, (input, context) -> {
            try {
                return context.waitForCallback("approval", String.class, (callbackId, stepContext) -> {});
            } catch (SuspendExecutionException e) {
                throw e;
            } catch (CallbackFailedException e) {
                observedErrorData.set(e.getErrorObject().errorData());
                throw e;
            }
        });

        assertEquals(ExecutionStatus.PENDING, runner.run("value").getStatus());
        runner.failCallback(
                runner.getCallbackId("approval-callback"),
                ErrorObject.builder()
                        .errorType("ExternalCallbackError")
                        .errorMessage("callback failed")
                        .errorData(unsupportedEnvelope)
                        .build());

        var result = runner.run("value");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(unsupportedEnvelope, observedErrorData.get());
        assertEquals(unsupportedEnvelope, result.getError().orElseThrow().errorData());
    }

    @Test
    void waitForCallbackReferenceEnvelopeIsNotLoaded() {
        var sequence = new AtomicInteger();
        var offloadCount = new AtomicInteger();
        var loadCount = new AtomicInteger();
        var values = new ConcurrentHashMap<String, String>();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                offloadCount.incrementAndGet();
                var reference = "memory://" + sequence.incrementAndGet();
                values.put(reference, serializedPayload);
                return OffloadedPayload.reference(reference, null);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                loadCount.incrementAndGet();
                return values.get(payload.reference());
            }
        };
        var producerContext = PayloadOffloadContext.forOperation(
                "arn:aws:lambda:us-east-1:123456789012:function:external:$LATEST/durable-execution/name/id",
                OperationIdentifier.of("callback-error", "callback-error", OperationSubType.CALLBACK),
                null,
                SerDesPayloadKind.EXCEPTION,
                null);
        var externalEnvelope =
                new PayloadCodec(null).offloadSerializedPayload("external-error-data", offloader, producerContext);
        offloadCount.set(0);
        var observedErrorData = new AtomicReference<String>();
        var config = DurableConfig.builder().withPayloadOffloader(offloader).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> {
                    try {
                        return context.waitForCallback("approval", String.class, (callbackId, stepContext) -> {});
                    } catch (SuspendExecutionException e) {
                        throw e;
                    } catch (CallbackFailedException e) {
                        observedErrorData.set(e.getErrorObject().errorData());
                        throw e;
                    }
                },
                config);

        assertEquals(ExecutionStatus.PENDING, runner.run("value").getStatus());
        runner.failCallback(
                runner.getCallbackId("approval-callback"),
                ErrorObject.builder()
                        .errorType("ExternalCallbackError")
                        .errorMessage("callback failed")
                        .errorData(externalEnvelope)
                        .build());

        var result = runner.run("value");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals(externalEnvelope, observedErrorData.get());
        assertEquals(0, loadCount.get());
        assertEquals(2, offloadCount.get());
    }

    @Test
    void disabledInvokeErrorIsOffloadedAtChildBoundary() {
        var sequence = new AtomicInteger();
        var loadCount = new AtomicInteger();
        var storedPayloads = new CopyOnWriteArrayList<StoredPayload>();
        var values = new ConcurrentHashMap<String, String>();
        var offloader = new PayloadOffloader() {
            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                storedPayloads.add(new StoredPayload(context.withOriginalValue(null), serializedPayload));
                var reference = "memory://" + sequence.incrementAndGet();
                values.put(reference, serializedPayload);
                return OffloadedPayload.reference(reference, null);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                loadCount.incrementAndGet();
                return values.get(payload.reference());
            }
        };
        var config = DurableConfig.builder().withPayloadOffloader(offloader).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.runInChildContext(
                        "child",
                        String.class,
                        child -> child.invoke(
                                "disabled-invoke",
                                "target",
                                "{}",
                                String.class,
                                InvokeConfig.builder()
                                        .payloadOffloader(PayloadOffloader.disabled())
                                        .build()),
                        RunInChildContextConfig.builder().build()),
                config);

        assertEquals(ExecutionStatus.PENDING, runner.run("value").getStatus());
        runner.failChainedInvoke(
                "disabled-invoke",
                ErrorObject.builder()
                        .errorType("RemoteError")
                        .errorMessage("remote failure")
                        .errorData("raw-error-data")
                        .build());

        var result = runner.run("value");
        var childError = result.getOperation("child").getContextDetails().error();

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertTrue(PayloadCodec.isOffloadEnvelope(childError.errorData()));
        assertTrue(
                PayloadCodec.isOffloadEnvelope(result.getError().orElseThrow().errorData()));
        assertEquals(2, storedPayloads.size());
        assertEquals(
                List.of("raw-error-data", "raw-error-data"),
                storedPayloads.stream().map(StoredPayload::serializedPayload).toList());
        assertTrue(storedPayloads.stream()
                .anyMatch(payload -> payload.context().operationType() == OperationType.CONTEXT));
        assertTrue(storedPayloads.stream()
                .anyMatch(payload -> payload.context().operationType() == OperationType.EXECUTION));
        assertEquals(1, loadCount.get());
    }

    @Test
    void nestedStandardInvokeMarkerErrorIsEscapedAtChildAndRootBoundaries() {
        var marker = "@aws-durable-payload:v2:{}";
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.runInChildContext(
                        "child",
                        String.class,
                        child -> child.invoke("standard-invoke", "standard", "{}", String.class),
                        RunInChildContextConfig.builder().build()));

        assertEquals(ExecutionStatus.PENDING, runner.run("value").getStatus());
        runner.failChainedInvoke(
                "standard-invoke",
                ErrorObject.builder()
                        .errorType("RemoteError")
                        .errorMessage("remote failure")
                        .errorData(marker)
                        .build());

        var result = runner.run("value");
        var childError = result.getOperation("child").getContextDetails().error();
        var rootError = result.getError().orElseThrow();
        var rootPayload = new JacksonSerDes()
                .deserialize(
                        rootError.errorData().substring("@aws-durable-payload:v1:".length()),
                        TypeToken.get(OffloadedPayload.class));

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals("RemoteError", rootError.errorType());
        assertTrue(PayloadCodec.isOffloadEnvelope(childError.errorData()));
        assertTrue(PayloadCodec.isOffloadEnvelope(rootError.errorData()));
        assertEquals(
                marker,
                new PayloadCodec(null)
                        .resolveSerializedPayload(rootError.errorData(), null, rootPayload.producerContext()));
    }

    @Test
    void rawInvokeErrorWorksWithStructuredPreviewsEnabled() throws IOException {
        var offloader = FileSystemPayloadOffloader.builder(payloadDirectory)
                .previewConfig(PreviewConfig.builder(PreviewMode.INCLUDE_ALL).build())
                .build();
        var config = DurableConfig.builder().withPayloadOffloader(offloader).build();
        var runner = LocalDurableTestRunner.create(
                String.class,
                (input, context) -> context.invoke(
                        "disabled-invoke",
                        "target",
                        "{}",
                        String.class,
                        InvokeConfig.builder()
                                .payloadOffloader(PayloadOffloader.disabled())
                                .build()),
                config);

        assertEquals(ExecutionStatus.PENDING, runner.run("value").getStatus());
        runner.failChainedInvoke(
                "disabled-invoke",
                ErrorObject.builder()
                        .errorType("RemoteError")
                        .errorMessage("remote failure")
                        .errorData("raw-error-data")
                        .build());

        var result = runner.run("value");

        assertEquals(ExecutionStatus.FAILED, result.getStatus());
        assertEquals("RemoteError", result.getError().orElseThrow().errorType());
        assertTrue(
                PayloadCodec.isOffloadEnvelope(result.getError().orElseThrow().errorData()));
        try (var files = Files.walk(payloadDirectory)) {
            assertTrue(files.anyMatch(Files::isRegularFile));
        }
    }

    @Test
    void defaultInvokePayloadPreservesStandardLambdaWireFormat() {
        var callerArn =
                "arn:aws:lambda:us-east-1:123456789012:function:caller:1/durable-execution/caller-execution/caller-invocation";
        var callerClient = new LocalMemoryExecutionClient();
        var config = DurableConfig.builder()
                .withDurableExecutionClient(callerClient)
                .withPayloadOffloader(
                        FileSystemPayloadOffloader.builder(payloadDirectory).build())
                .build();
        var execution = executionOperation("caller-invocation", "caller-execution", "\"request\"");

        var output = DurableExecutor.execute(
                durableInput(callerArn, execution, List.of(), List.of()),
                null,
                TypeToken.get(String.class),
                (input, context) ->
                        context.invoke("call-standard", "standard", new CrossInvokeRequest(input), String.class),
                config);

        assertEquals(ExecutionStatus.PENDING, output.status());
        var invokePayload = callerClient.getOperationUpdates().stream()
                .filter(update ->
                        update.type() == OperationType.CHAINED_INVOKE && update.action() == OperationAction.START)
                .findFirst()
                .orElseThrow()
                .payload();
        assertEquals("{\"value\":\"request\"}", invokePayload);
        assertTrue(!ChainedInvokePayloadFrame.isFramed(invokePayload));
    }

    private static DurableExecutionInput durableInput(
            String executionArn, Operation executionOperation, List<Operation> operations, List<String> updatedIds) {
        return durableInput(executionArn, executionOperation, operations, updatedIds, InvocationSource.DIRECT);
    }

    private static DurableExecutionInput durableInput(
            String executionArn,
            Operation executionOperation,
            List<Operation> operations,
            List<String> updatedIds,
            InvocationSource invocationSource) {
        var allOperations = new ArrayList<Operation>();
        allOperations.add(executionOperation);
        allOperations.addAll(operations);
        return new DurableExecutionInput(
                executionArn,
                "checkpoint-token",
                CheckpointUpdatedExecutionState.builder()
                        .operations(allOperations)
                        .build(),
                updatedIds,
                invocationSource);
    }

    private static Operation executionOperation(String id, String name, String inputPayload) {
        return Operation.builder()
                .id(id)
                .name(name)
                .type(OperationType.EXECUTION)
                .status(OperationStatus.STARTED)
                .startTimestamp(Instant.now())
                .executionDetails(
                        ExecutionDetails.builder().inputPayload(inputPayload).build())
                .build();
    }

    private static PayloadOffloader replayFailingOffloader(
            Predicate<PayloadOffloadContext> shouldFail, AtomicBoolean failLoads) {
        return new PayloadOffloader() {
            private final AtomicInteger sequence = new AtomicInteger();
            private final Map<String, String> values = new ConcurrentHashMap<>();

            @Override
            public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
                if (context.operationSubType() == software.amazon.lambda.durable.model.OperationSubType.MAP) {
                    return OffloadedPayload.inline(serializedPayload);
                }
                var reference = "memory://" + sequence.incrementAndGet();
                values.put(reference, serializedPayload);
                return OffloadedPayload.reference(reference, null);
            }

            @Override
            public String load(OffloadedPayload payload, PayloadOffloadContext context) {
                if (failLoads.get() && shouldFail.test(context)) {
                    throw new RetryablePayloadOffloadException("storage unavailable during replay");
                }
                return payload.data() != null ? payload.data() : values.get(payload.reference());
            }
        };
    }

    private static DurableOperationException sourceBackedFailure(
            PayloadOffloader sourceOffloader, String producerName) {
        var producerContext = PayloadOffloadContext.forOperation(
                "arn:aws:lambda:us-east-1:123456789012:function:source:$LATEST/durable-execution/name/id",
                OperationIdentifier.of(producerName, producerName, OperationSubType.STEP),
                null,
                SerDesPayloadKind.EXCEPTION,
                1);
        var errorData = new PayloadCodec(null)
                .serialize(
                        new IllegalStateException("forwarded failure"),
                        new JacksonSerDes(),
                        sourceOffloader,
                        producerContext);
        var error = ErrorObject.builder()
                .errorType(IllegalStateException.class.getName())
                .errorMessage("forwarded failure")
                .errorData(errorData)
                .build();
        var operation = Operation.builder()
                .id(producerName)
                .type(OperationType.STEP)
                .status(OperationStatus.FAILED)
                .build();
        return new DurableOperationException(operation, error).withPayloadSource(sourceOffloader, producerContext);
    }

    private static SerDes markerExceptionSerDes() {
        var delegate = new JacksonSerDes();
        return new SerDes() {
            @Override
            public String serialize(Object value) {
                return value instanceof Throwable ? "@aws-durable-payload:v2:{}" : delegate.serialize(value);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T deserialize(String data, TypeToken<T> typeToken) {
                if ("@aws-durable-payload:v2:{}".equals(data)) {
                    return (T) new IllegalStateException("branch failure");
                }
                return delegate.deserialize(data, typeToken);
            }
        };
    }

    record CrossInvokeRequest(String value) {}

    record CrossInvokeResponse(String value) {}

    private static final class CountingPayloadOffloader implements PayloadOffloader {
        private final AtomicInteger offloadCount = new AtomicInteger();

        @Override
        public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
            Objects.requireNonNull(serializedPayload, "serializedPayload cannot be null");
            offloadCount.incrementAndGet();
            return OffloadedPayload.inline(serializedPayload);
        }

        @Override
        public String load(OffloadedPayload payload, PayloadOffloadContext context) {
            return payload.data();
        }

        private int offloadCount() {
            return offloadCount.get();
        }
    }

    private static final class ContextKeyedPayloadOffloader implements PayloadOffloader {
        private final AtomicInteger sequence = new AtomicInteger();
        private final Map<String, StoredPayload> values = new ConcurrentHashMap<>();

        @Override
        public OffloadedPayload offload(String serializedPayload, PayloadOffloadContext context) {
            var reference = "memory://" + sequence.incrementAndGet();
            values.put(reference, new StoredPayload(context.withOriginalValue(null), serializedPayload));
            return OffloadedPayload.reference(reference, null);
        }

        @Override
        public String load(OffloadedPayload payload, PayloadOffloadContext context) {
            var stored = values.get(payload.reference());
            if (stored == null || !stored.context().equals(context)) {
                throw new PayloadOffloadException("payload loaded with a different producer context");
            }
            return stored.serializedPayload();
        }
    }

    private record StoredPayload(PayloadOffloadContext context, String serializedPayload) {}
}
