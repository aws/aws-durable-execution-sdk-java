// Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
package software.amazon.lambda.durable.serde;

import java.util.ArrayList;
import java.util.List;
import software.amazon.lambda.durable.exception.RetryableSerDesException;
import software.amazon.lambda.durable.exception.SerDesException;

final class ChainedSerDesStage<I, O> implements SerDesStage<I, O> {
    private final List<SerDesStage<?, ?>> stages;

    private ChainedSerDesStage(List<SerDesStage<?, ?>> stages) {
        for (int index = 0; index < stages.size() - 1; index++) {
            var stage = stages.get(index);
            if (stage.isTerminalPipelineStage()) {
                throw new IllegalArgumentException(String.format(
                        "SerDes pipeline stage %d (%s) must be the final stage",
                        index + 1, stage.getClass().getName()));
            }
        }
        this.stages = List.copyOf(stages);
    }

    static <I, M, O> SerDesStage<I, O> of(SerDesStage<I, M> first, SerDesStage<? super M, O> second) {
        var stages = new ArrayList<SerDesStage<?, ?>>();
        addFlattened(stages, first);
        addFlattened(stages, second);
        return new ChainedSerDesStage<>(stages);
    }

    List<SerDesStage<?, ?>> stages() {
        return stages;
    }

    @Override
    @SuppressWarnings("unchecked")
    public O serialize(I value) {
        Object current = value;
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            try {
                current = ((SerDesStage<Object, Object>) stage).serialize(current);
                if (current == null) {
                    throw new SerDesException("Stage returned null for a non-null value");
                }
            } catch (Throwable failure) {
                throw stageFailure(index + 1, stage, "serialize", failure);
            }
        }
        return (O) current;
    }

    @Override
    @SuppressWarnings("unchecked")
    public I deserialize(O data) {
        return (I) deserializePipelineStage(data).value();
    }

    @Override
    @SuppressWarnings("unchecked")
    public SerDesStageResult deserializePipelineStage(O data) {
        Object current = data;
        for (int index = stages.size() - 1; index >= 0; index--) {
            var stage = stages.get(index);
            try {
                var result = ((SerDesStage<Object, Object>) stage).deserializePipelineStage(current);
                if (result == null) {
                    throw new SerDesException("Stage returned a null pipeline result");
                }
                current = result.value();
                if (result.skipRemainingStages()) {
                    return result;
                }
            } catch (Throwable failure) {
                throw stageFailure(index + 1, stage, "deserialize", failure);
            }
        }
        return SerDesStageResult.continueWith(current);
    }

    @Override
    public boolean requiresDurableContext() {
        return stages.stream().anyMatch(SerDesStage::requiresDurableContext);
    }

    @Override
    public boolean isTerminalPipelineStage() {
        return stages.get(stages.size() - 1).isTerminalPipelineStage();
    }

    private static void addFlattened(List<SerDesStage<?, ?>> target, SerDesStage<?, ?> stage) {
        if (stage instanceof ChainedSerDesStage<?, ?> chained) {
            target.addAll(chained.stages);
        } else {
            target.add(stage);
        }
    }

    private static RuntimeException stageFailure(int index, SerDesStage<?, ?> stage, String action, Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        var message = String.format(
                "SerDes pipeline stage %d (%s) failed to %s",
                index, stage.getClass().getName(), action);
        if (failure instanceof RetryableSerDesException) {
            return new RetryableSerDesException(message, failure);
        }
        return new SerDesException(message, failure);
    }
}
